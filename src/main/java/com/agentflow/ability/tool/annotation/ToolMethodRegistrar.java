package com.agentflow.ability.tool.annotation;

import java.lang.reflect.*;
import java.util.Collection;
import java.util.Map;

import com.agentflow.ability.tool.dto.ToolDefinition;
import com.agentflow.ability.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * @AgentTool 注解扫描器（T5.2，架构 9.2 来源①）——启动完成后遍历所有 Spring Bean，
 * 把带 {@link ToolMethod} 的 public 方法反射转换为 {@link ToolDefinition} 注册进注册中心。
 *
 * <p>重名工具由注册中心抛 {@link com.agentflow.ability.tool.ToolConflictException} → 启动失败（显式暴露冲突）。
 * 扫描逻辑抽成 {@link #registerToolMethods(Object)}，单测可直接对普通对象调用，不依赖 Spring 上下文。
 */
@Component
public class ToolMethodRegistrar implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ToolMethodRegistrar.class);

    private final ApplicationContext applicationContext;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper mapper;

    public ToolMethodRegistrar(ApplicationContext applicationContext, ToolRegistry toolRegistry, ObjectMapper mapper) {
        this.applicationContext = applicationContext;
        this.toolRegistry = toolRegistry;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            try {
                registerToolMethods(applicationContext.getBean(beanName));
            } catch (Exception e) {
                log.info(String.valueOf(e));
            }
        }
    }

    /**
     * 单个对象上所有 @AgentTool public 方法的 ToolDescriptor。
     * {@link Class#getMethods()} 只返回 public（含继承），非 public 的 @AgentTool 方法天然被跳过。
     */
    private void registerToolMethods(Object bean) {
        for (Method method : bean.getClass().getMethods()) {
            ToolMethod toolMethod = method.getAnnotation(ToolMethod.class);
            if (toolMethod == null) {
                continue;
            }
            ToolDefinition toolDefinition = new ToolDefinition();
            toolDefinition.setName(toolMethod.name());
            toolDefinition.setDescription(toolMethod.description());
            toolDefinition.setParameters(buildParameters(method));
            toolDefinition.setToolInvoker(args -> toolInvoke(bean, method, args));
            toolRegistry.register(toolDefinition);
        }
    }

    /**
     * 方法全部参数 → {@code {type: object, properties, required}}。
     */
    private ObjectNode buildParameters(Method method) {
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", JsonSchemaType.OBJECT.value());
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");
        for (Parameter parameter : method.getParameters()) {
            properties.set(parameter.getName(), buildTypeSchema(parameter.getParameterizedType()));
            required.add(parameter.getName());
        }
        return parameters;
    }

    /**
     * 一个参数的类型 → JSON Schema。
     *
     * <p>只收 {@link Type}（完整类型，含泛型）——擦除后的 {@code Class} 可以从它推出来
     * （见 {@link #convertToClass(Type)}），不必单传。
     */
    private JsonNode buildTypeSchema(Type fullType) {
        Class<?> rawType = convertToClass(fullType);
        if (rawType.isEnum()) {
            ObjectNode node = buildTypeNode(JsonSchemaType.STRING);
            ArrayNode enumValues = node.putArray("enum");
            for (Object constant : rawType.getEnumConstants()) {
                enumValues.add(String.valueOf(constant));
            }
            return node;
        }
        if (rawType == String.class || rawType == char.class || rawType == Character.class) {
            return buildTypeNode(JsonSchemaType.STRING);
        }
        if (rawType == boolean.class || rawType == Boolean.class) {
            return buildTypeNode(JsonSchemaType.BOOLEAN);
        }
        if (rawType == int.class || rawType == Integer.class || rawType == long.class || rawType == Long.class
                || rawType == short.class || rawType == Short.class || rawType == byte.class || rawType == Byte.class) {
            return buildTypeNode(JsonSchemaType.INTEGER);
        }
        if (rawType == double.class || rawType == Double.class || rawType == float.class || rawType == Float.class) {
            return buildTypeNode(JsonSchemaType.NUMBER);
        }
        if (Map.class.isAssignableFrom(rawType)) {
            return buildTypeNode(JsonSchemaType.OBJECT);
        }
        if (Collection.class.isAssignableFrom(rawType) || rawType.isArray()) {
            ObjectNode node = buildTypeNode(JsonSchemaType.ARRAY);
            Type elementType = getElementType(fullType);
            if (elementType != null) {
                node.set("items", buildTypeSchema(elementType));
            }
            return node;
        }
        return buildTypeNode(JsonSchemaType.OBJECT); // POJO 等：浅层 V1 不递归
    }

    /**
     * 完整类型（含泛型）→ 擦除后的原始 {@code Class}：{@code List<String>} → {@code List.class}；
     * {@code String} → {@code String.class}。拿不到时兜底 {@code Object.class}（走 POJO 分支）。
     */
    private static Class<?> convertToClass(Type fullType) {
        if (fullType instanceof Class<?> c) {
            return c;
        }
        if (fullType instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        if (fullType instanceof GenericArrayType gat) {
            return Array.newInstance(convertToClass(gat.getGenericComponentType()), 0).getClass();
        }
        return Object.class;
    }

    /**
     * 造一个最简的 schema 节点：{@code {type: xxx}}。
     */
    private ObjectNode buildTypeNode(JsonSchemaType type) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type.value());
        return node;
    }

    /**
     * List/Set/数组的元素类型：泛型单类型参数或数组分量；取不到返回 null。
     *
     * <p>返回 {@link Type} 而不是 {@code Class}——**元素自己可能带泛型**
     * （{@code List<Map<String,Object>>} 的元素是 {@code Map<String,Object>}），
     * 那时只有 {@code Type} 装得下；返回 {@code Class} 会把泛型丢掉，
     * 导致 {@code List<Map<String,Object>>} 退化成没有 {@code items} 的 {@code {type:array}}。
     */
    private static Type getElementType(Type fullType) {
        if (fullType instanceof Class<?> c && c.isArray()) {
            return c.getComponentType();
        }
        if (fullType instanceof GenericArrayType gat) {
            return gat.getGenericComponentType();
        }
        if (fullType instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length == 1) {
                return args[0];
            }
        }
        return null;
    }

    /**
     * 反射调用：按参数名从 args 取参（Spring Boot 默认 -parameters 保留参数名）→ coerce 到目标类型 → invoke。
     */
    private Object toolInvoke(Object bean, Method method, Map<String, Object> args) {
        Parameter[] params = method.getParameters();
        Object[] values = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            String paramName = params[i].getName();
            if (!args.containsKey(paramName)) {
                throw new IllegalArgumentException("工具缺少参数: " + method.getName() + "." + paramName);
            }
            values[i] = convertToParameterType(args.get(paramName), params[i].getType());
        }
        try {
            return method.invoke(bean, values);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("工具方法不可访问: " + method.getName(), e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("工具执行失败: " + method.getName(), e.getCause());
        }
    }

    /**
     * LLM 传来的参数（JSON 数字/字符串/布尔/数组/对象）→ Java 目标类型。
     * 数字字符串兼容（"3"→3，同 TemplateResolver 决策）；其余原样传入。
     */
    private static Object convertToParameterType(Object value, Class<?> parameterType) {
        if (value == null) {
            return null;
        }
        if (parameterType == int.class || parameterType == Integer.class) {
            return (value instanceof Number n) ? n.intValue() : Integer.parseInt(value.toString());
        }
        if (parameterType == long.class || parameterType == Long.class) {
            return (value instanceof Number n) ? n.longValue() : Long.parseLong(value.toString());
        }
        if (parameterType == double.class || parameterType == Double.class) {
            return (value instanceof Number n) ? n.doubleValue() : Double.parseDouble(value.toString());
        }
        if (parameterType == float.class || parameterType == Float.class) {
            return (value instanceof Number n) ? n.floatValue() : Float.parseFloat(value.toString());
        }
        if (parameterType == boolean.class || parameterType == Boolean.class) {
            return (value instanceof Boolean b) ? b : Boolean.parseBoolean(value.toString());
        }
        if (parameterType == String.class) {
            return value.toString();
        }
        return parameterType.cast(value); // List / Map / 同类型对象原样
    }

    /**
     * JSON Schema 的 {@code type} 关键字取值。
     */
    private enum JsonSchemaType {

        STRING("string"),

        BOOLEAN("boolean"),

        INTEGER("integer"),

        NUMBER("number"),

        OBJECT("object"),

        ARRAY("array");

        private final String value;

        JsonSchemaType(String value) {
            this.value = value;
        }

        /**
         * JSON Schema 里该写的字符串。
         */
        String value() {
            return value;
        }
    }
}
