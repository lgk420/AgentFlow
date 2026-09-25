package com.agentflow.ability.tool;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具描述（T5.1，架构 9.1）——注册中心里"一个工具的完整说明 + 执行器"。
 *
 * <p>{@code parameters} 是原始 JSON Schema（Jackson JsonNode 原样承载，同 {@code OutputSchema} 思路）：
 * T5.3 交给 json-schema-validator 校验入参，同时 T4.3 循环把同一份转成模型 function-calling 定义——
 * 一套 schema 两个用途（QA/架构 9.3）。不泄漏 networknt 的 JsonSchema 类型到核心模型。
 */
public class ToolDescriptor {

    /**
     * 工具名，注册中心内唯一（T5.1 重名注册报错）。
     */
    private String name;

    /**
     * 给 LLM 看的自然语言描述（决定模型何时选这个工具）。
     */
    private String description;

    /**
     * 参数 JSON Schema——入参校验（T5.3）与 LLM function 定义共用，缺省 null（不校验参数）。
     */
    private JsonNode parameters;

    /**
     * 工具分组（展示 / 归类用），可空。
     */
    private String category;

    /**
     * 单次调用超时毫秒数，缺省 {@value #DEFAULT_TIMEOUT_MS}。
     */
    private long timeoutMs;

    /**
     * 执行器：方法引用 / MCP 工具 / 远程的统一适配。
     */
    private ToolInvoker invoker;

    /**
     * timeoutMs 缺省值（10 秒）。
     */
    public static final long DEFAULT_TIMEOUT_MS = 10_000L;

    public ToolDescriptor() {
    }

    public ToolDescriptor(String name, String description, JsonNode parameters, ToolInvoker invoker) {
        this(name, description, parameters, null, DEFAULT_TIMEOUT_MS, invoker);
    }

    public ToolDescriptor(String name, String description, JsonNode parameters,
                          String category, long timeoutMs, ToolInvoker invoker) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.category = category;
        this.timeoutMs = timeoutMs;
        this.invoker = invoker;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public JsonNode getParameters() {
        return parameters;
    }

    public void setParameters(JsonNode parameters) {
        this.parameters = parameters;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public ToolInvoker getInvoker() {
        return invoker;
    }

    public void setInvoker(ToolInvoker invoker) {
        this.invoker = invoker;
    }
}