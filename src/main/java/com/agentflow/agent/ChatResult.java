package com.agentflow.agent;

import java.util.List;

/**
 * 带工具对话的结果：要么返回文本（模型直接回答），要么返回工具调用列表（模型要求调工具）。
 */
public class ChatResult {

    /**
     * 模型文本回复；有工具调用时可能为 null。
     */
    private String text;

    /**
     * 工具调用列表；无工具调用时为空。
     */
    private List<ToolCall> toolCalls = List.of();

    public ChatResult() {
    }

    public ChatResult(String text, List<ToolCall> toolCalls) {
        this.text = text;
        this.toolCalls = toolCalls;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    /**
     * 是否要求调用工具（工具调用列表非空）。
     */
    public boolean wantsTools() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
