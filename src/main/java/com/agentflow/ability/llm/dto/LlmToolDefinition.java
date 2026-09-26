package com.agentflow.ability.llm.dto;

import java.util.Map;
import java.util.function.Function;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * 轻量工具定义（T4.3，QA 49 修订①）——喂给模型的 function-calling 契约。
 *
 * <p>由 {@code ToolDescriptor}（注册中心）组装：name / description / parameters(JSON Schema)。
 * 转成 Spring AI 的 ToolCallback 由它自己负责（{@link #toToolCallback()}，仅作 schema 载体，
 * 执行仍由循环手动调 ToolRegistry）。{@code LlmClient} 的<b>接口签名</b>仍只出现本包类型——
 * 框架依赖止步于本类内部，不向上泄漏。
 */
public class LlmToolDefinition {

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

    public LlmToolDefinition(String name, String description, String inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    /**
     * 转成 Spring AI 的 {@link ToolCallback}：FunctionToolCallback 只当 <b>schema 载体</b>
     * （function 抛错 = 框架不应执行工具，执行由循环手动 ToolRegistry.invoke，见 QA 49 修订①）。
     *
     * <p><b>必须同时设 {@code inputSchema} + {@code inputType}</b>（T4.5 踩坑：只设 inputSchema 时
     * Spring AI 1.0.0 的 {@code build()} 抛 "inputType cannot be null"——真实客户端的 chatWithTools 一直潜伏此 bug，
     * T4.3 用 Stub 客户端测没暴露）。inputType=Map.class 仅作占位。
     *
     * <p>函数类型用 {@code Function<Map,Object>} 对齐 inputType（Bug 03）：若内部工具执行被意外打开，
     * 框架按 inputType 反序列化参数成 Map 再调函数——用 Map 签名避免 "LinkedHashMap cannot be cast to String"
     * 的误导性错误，直接抛明确 IllegalStateException。
     */
    public ToolCallback toToolCallback() {
        return FunctionToolCallback.builder(name,
                        (Function<Map<String, Object>, Object>) input -> {
                            throw new IllegalStateException("工具应由 AgenticLoop 手动调用：" + name);
                        })
                .description(description)
                .inputSchema(inputSchema)
                .inputType(Map.class)
                .build();
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
