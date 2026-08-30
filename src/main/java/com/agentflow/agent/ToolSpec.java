package com.agentflow.agent;

/**
 * 轻量工具定义（T4.3，QA 49 修订①）——喂给模型的 function-calling 契约。
 *
 * <p>由 {@code ToolDescriptor}（注册中心）组装：name / description / parameters(JSON Schema)。
 * 网关用它转成 Spring AI 的 ToolCallback（仅作 schema 载体，执行仍由循环手动调注册中心），
 * 不把 {@code tool} 包或 Spring AI 的类型泄漏进 {@code LlmGateway} 接口。
 */
public class ToolSpec {

    /**
     * 工具名（注册中心内唯一）。
     */
    private final String name;

    /**
     * 给 LLM 看的自然语言描述。
     */
    private final String description;

    /**
     * 参数 JSON Schema（原样字符串）；null 表示不约束参数。
     */
    private final String inputSchema;

    public ToolSpec(String name, String description, String inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getInputSchema() {
        return inputSchema;
    }
}
