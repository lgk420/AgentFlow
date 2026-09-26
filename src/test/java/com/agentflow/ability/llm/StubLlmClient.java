package com.agentflow.ability.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.agentflow.ability.llm.dto.LlmChatMessage;
import com.agentflow.ability.llm.dto.LlmChatResult;
import com.agentflow.ability.llm.dto.LlmToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * LLM 客户端桩（测试替身，QA 36）——返回可配置的固定数据，供单测 / 无 Ollama 时使用。
 *
 * <p>注意：<b>不是</b> @Component（避免与 {@link SpringAiLlmClient} 形成两个 LlmClient bean，注入歧义）；
 * 测试里直接 new 或作为测试替身注入。
 *
 * <p>T4.3 起 {@link #chatWithTools} 支持<b>脚本化多轮</b>：{@link #setToolDialogues(LlmChatResult...)} 预置
 * 一组响应按调用次数依次出队（"第一轮要求调工具 → 第二轮给最终答案"），耗尽后回落 {@link #textOutput}。
 */
public class StubLlmClient implements LlmClient {

    /**
     * chat / chatWithTools 返回的文本。
     */
    private volatile String textOutput = "stub 回复";

    /**
     * 最近一次收到的 user prompt（测试断言模板是否已解析）。
     */
    private volatile String lastUserPrompt;

    /**
     * chatWithTools 最近一次收到的 systemPrompt。
     */
    private volatile String lastSystemPrompt;

    /**
     * chatWithTools 最近一次收到的历史消息（断言循环是否追加了 assistant 工具调用轮与 tool 结果轮）。
     */
    private volatile List<LlmChatMessage> lastHistory;

    /**
     * chatWithTools 最近一次收到的工具定义。
     */
    private volatile List<LlmToolDefinition> lastTools;

    /**
     * chatStructured 返回的结构化结果（可配置，默认空 Map）。
     *
     * <p><b>桩只负责返回预置数据</b>——「拼 schema 指令 / 剥 markdown 围栏 / JSON 解析」是
     * {@link SpringAiLlmClient} 的实现细节，它的测试在 {@code SpringAiLlmClientChatStructuredTest}，
     * 不在这里绕桩验证。
     */
    private volatile Map<String, Object> structuredOutput = Map.of();

    /**
     * chatStructured 最近一次收到的 prompt（断言模板是否已解析）。
     */
    private volatile String lastStructuredPrompt;

    /**
     * 脚本化工具对话队列：按调用次数依次出队。
     */
    private final List<LlmChatResult> toolDialogues = new ArrayList<>();

    /**
     * 下一出队下标。
     */
    private volatile int toolDialogueIndex = 0;

    public void setTextOutput(String textOutput) {
        this.textOutput = textOutput;
    }

    public void setStructuredOutput(Map<String, Object> structuredOutput) {
        this.structuredOutput = structuredOutput;
    }

    public String getLastStructuredPrompt() {
        return lastStructuredPrompt;
    }

    public String getLastUserPrompt() {
        return lastUserPrompt;
    }

    public String getLastSystemPrompt() {
        return lastSystemPrompt;
    }

    public List<LlmChatMessage> getLastHistory() {
        return lastHistory;
    }

    public List<LlmToolDefinition> getLastTools() {
        return lastTools;
    }

    /**
     * 脚本化工具对话：按调用次数依次返回预置结果；耗尽后回落 {@link #textOutput}（无工具调用）。
     */
    public void setToolDialogues(LlmChatResult... responses) {
        toolDialogues.clear();
        toolDialogues.addAll(List.of(responses));
        toolDialogueIndex = 0;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        this.lastUserPrompt = userPrompt;
        return textOutput;
    }

    @Override
    public Map<String, Object> chatStructured(String prompt, JsonNode schema) {
        this.lastStructuredPrompt = prompt;
        return structuredOutput;
    }

    @Override
    public LlmChatResult chatWithTools(String systemPrompt, List<LlmChatMessage> history, List<LlmToolDefinition> tools) {
        this.lastSystemPrompt = systemPrompt;
        this.lastHistory = history;
        this.lastTools = tools;
        if (toolDialogueIndex < toolDialogues.size()) {
            return toolDialogues.get(toolDialogueIndex++);
        }
        return new LlmChatResult(textOutput, List.of());
    }
}
