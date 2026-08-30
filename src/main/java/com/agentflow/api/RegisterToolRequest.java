package com.agentflow.api;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 动态注册工具请求体（T5.6，来源②编程式注册的 HTTP 化）。
 *
 * <p>字段：name（注册中心内唯一）、description（给 LLM 看）、
 * parameters（参数 JSON Schema，可空=不校验参数）、url（回调地址——invoke 时 POST 参数过去，代码跑在回调端）。
 */
public class RegisterToolRequest {

    /**
     * 工具名，注册中心内唯一；重名抛 {@link com.agentflow.tool.ToolConflictException}。
     */
    private String name;

    /**
     * 给 LLM 看的自然语言描述。
     */
    private String description;

    /**
     * 参数 JSON Schema（T5.3 校验 + LLM function 定义一套两用）；null 表示不校验参数。
     */
    private JsonNode parameters;

    /**
     * 回调地址：引擎 invoke 时 POST 参数过去，解析响应（{@link com.agentflow.tool.RemoteToolInvoker}）。
     */
    private String url;

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

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
