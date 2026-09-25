package com.agentflow.ability.tool.annotation;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 方法签名 → 参数 JSON Schema（T5.2，架构 9.2"JSON Schema 推断"）。
 *
 * <p>浅层 V1：基本类型 / String / enum / List / Map；POJO 参数一律 {@code object} 不递归。
 * 同一份 schema 两个用途：T5.3 入参校验 + T4.3 喂给模型的 function 定义（QA 49）。
 */
class ToolSchemaGenerator {

    private final ObjectMapper mapper;

    ToolSchemaGenerator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 方法全部参数 → {@code {type: object, properties, required}}。
     */
    ObjectNode schemaFor(Method method) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (Parameter p : method.getParameters()) {
            properties.set(p.getName(), typeSchema(p.getParameterizedType(), p.getType()));
            required.add(p.getName());
        }
        return schema;
    }

    private JsonNode typeSchema(Type genericType, Class<?> type) {
        if (type.isEnum()) {
            ObjectNode node = typeNode("string");
            ArrayNode enumValues = node.putArray("enum");
            for (Object constant : type.getEnumConstants()) {
                enumValues.add(String.valueOf(constant));
            }
            return node;
        }
        if (type == String.class || type == char.class || type == Character.class) {
            return typeNode("string");
        }
        if (type == boolean.class || type == Boolean.class) {
            return typeNode("boolean");
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == short.class || type == Short.class || type == byte.class || type == Byte.class) {
            return typeNode("integer");
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) {
            return typeNode("number");
        }
        if (Map.class.isAssignableFrom(type)) {
            return typeNode("object");
        }
        if (Collection.class.isAssignableFrom(type) || type.isArray()) {
            ObjectNode node = typeNode("array");
            Class<?> elementType = elementType(genericType, type);
            if (elementType != null) {
                node.set("items", typeSchema(elementType, elementType));
            }
            return node;
        }
        return typeNode("object"); // POJO 等：浅层 V1 不递归
    }

    private ObjectNode typeNode(String type) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        return node;
    }

    /**
     * List/Set/数组的元素类型：泛型单类型参数或数组分量；取不到返回 null。
     */
    private static Class<?> elementType(Type genericType, Class<?> rawType) {
        if (rawType.isArray()) {
            return rawType.getComponentType();
        }
        if (genericType instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class<?> c) {
                return c;
            }
        }
        return null;
    }
}
