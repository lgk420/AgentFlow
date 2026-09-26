package com.agentflow.ability.llm.dto;

import java.util.List;

/**
 * 带工具对话的结果：要么返回文本（模型直接回答），要么返回工具调用列表（模型要求调工具）。
 */
public class LlmChatResult {

    /**
     * 模型文本回复；有工具调用时可能为 null。
     */
    private String text;

    /**
     * 工具调用列表；无工具调用时为空。
     */
    private List<LlmToolCall> llmToolCalls;

    public LlmChatResult(String text, List<LlmToolCall> llmToolCalls) {
        this.text = text;
        this.llmToolCalls = llmToolCalls;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<LlmToolCall> getToolCalls() {
        return llmToolCalls;
    }

    public void setToolCalls(List<LlmToolCall> llmToolCalls) {
        this.llmToolCalls = llmToolCalls;
    }

    /**
     * 是否要求调用工具（工具调用列表非空）。
     */
    public boolean wantsTools() {
        return llmToolCalls != null && !llmToolCalls.isEmpty();
    }
}
