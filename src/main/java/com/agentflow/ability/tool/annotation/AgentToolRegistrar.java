package com.agentflow.ability.tool.annotation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.agentflow.ability.tool.ToolDescriptor;
import com.agentflow.ability.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * @AgentTool 注解扫描器（T5.2，架构 9.2 来源①）——启动完成后遍历所有 Spring Bean，
 * 把带 {@link AgentTool} 的 public 方法反射转换为 {@link ToolDescriptor} 注册进注册中心。
 *
 * <p>重名工具由注册中心抛 {@link com.agentflow.ability.tool.ToolConflictException} → 启动失败（显式暴露冲突）。
 * 扫描逻辑抽成 {@link #descriptorsOf(Object)}，单测可直接对普通对象调用，不依赖 Spring 上下文。
 */
@Component
public class AgentToolRegistrar implements ApplicationRunner {

    private final ApplicationContext applicationContext;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper mapper;

    public AgentToolRegistrar(ApplicationContext applicationContext, ToolRegistry toolRegistry, ObjectMapper mapper) {
        this.applicationContext = applicationContext;
        this.toolRegistry = toolRegistry;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean;
            try {
                bean = applicationContext.getBean(beanName);
            } catch (Exception e) {
                continue; // 工厂 / 懒加载等拿不到实例的 bean 跳过
            }
            for (ToolDescriptor descriptor : descriptorsOf(bean)) {
                toolRegistry.register(descriptor); // 重名冲突在此抛异常 → 启动失败
            }
        }
    }

    /**
     * 单个对象上所有 @AgentTool public 方法的 ToolDescriptor。
     * {@link Class#getMethods()} 只返回 public（含继承），非 public 的 @AgentTool 方法天然被跳过。
     */
    public List<ToolDescriptor> descriptorsOf(Object bean) {
        List<ToolDescriptor> result = new ArrayList<>();
        for (Method method : bean.getClass().getMethods()) {
            AgentTool agentTool = method.getAnnotation(AgentTool.class);
            if (agentTool == null) {
                continue;
            }
            result.add(toDescriptor(bean, method, agentTool));
        }
        return result;
    }

    private ToolDescriptor toDescriptor(Object bean, Method method, AgentTool agentTool) {
        String name = agentTool.name().isBlank() ? method.getName() : agentTool.name();
        String description = agentTool.description().isBlank() ? method.getName() : agentTool.description();
        ToolSchemaGenerator generator = new ToolSchemaGenerator(mapper);
        return new ToolDescriptor(name, description, generator.schemaFor(method), args -> invoke(bean, method, args));
    }

    /**
     * 反射调用：按参数名从 args 取参（Spring Boot 默认 -parameters 保留参数名）→ coerce 到目标类型 → invoke。
     */
    private Object invoke(Object bean, Method method, Map<String, Object> args) {
        Parameter[] params = method.getParameters();
        Object[] values = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            String paramName = params[i].getName();
            if (!args.containsKey(paramName)) {
                throw new IllegalArgumentException("工具缺少参数: " + method.getName() + "." + paramName);
            }
            values[i] = coerce(args.get(paramName), params[i].getType());
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
    private static Object coerce(Object value, Class<?> type) {
        if (value == null) {
            return null;
        }
        if (type == int.class || type == Integer.class) {
            return toInt(value);
        }
        if (type == long.class || type == Long.class) {
            return toLong(value);
        }
        if (type == double.class || type == Double.class) {
            return toDouble(value);
        }
        if (type == float.class || type == Float.class) {
            return toFloat(value);
        }
        if (type == boolean.class || type == Boolean.class) {
            return (value instanceof Boolean b) ? b : Boolean.parseBoolean(value.toString());
        }
        if (type == String.class) {
            return value.toString();
        }
        return type.cast(value); // List / Map / 同类型对象原样
    }

    private static int toInt(Object value) {
        return (value instanceof Number n) ? n.intValue() : Integer.parseInt(value.toString());
    }

    private static long toLong(Object value) {
        return (value instanceof Number n) ? n.longValue() : Long.parseLong(value.toString());
    }

    private static double toDouble(Object value) {
        return (value instanceof Number n) ? n.doubleValue() : Double.parseDouble(value.toString());
    }

    private static float toFloat(Object value) {
        return (value instanceof Number n) ? n.floatValue() : Float.parseFloat(value.toString());
    }
}
