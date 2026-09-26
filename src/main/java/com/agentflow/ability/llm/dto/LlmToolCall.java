package com.agentflow.ability.llm.dto;

/**
 * 一次工具调用请求（AgenticLoop 手写循环用）——轻量类型，不泄漏框架的 ToolCall。
 */
public class LlmToolCall {

    /**
     * 工具调用 id（模型侧生成）。TOOL 结果消息靠它与 assistant 的 tool_call 配对
     * （Spring AI 的 ToolResponseMessage.ToolResponse(id, ...)）。
     */
    private String id;

    /**
     * 工具名（须注册在工具注册中心）。
     */
    private String name;

    /**
     * 参数（JSON 字符串）。
     */
    private String arguments;

    public LlmToolCall(String id, String name, String arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }
}
