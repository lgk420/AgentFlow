package com.agentflow.ability.llm;

import java.util.List;

/**
 * 轻量对话消息（T4.3 AgenticLoop 循环用）——不泄漏 Spring AI 的 Message 类型。
 *
 * <p>三种角色承载不同内容（QA 49）：
 * <ul>
 *   <li>{@link Role#USER}：content 为用户提问文本。</li>
 *   <li>{@link Role#ASSISTANT}：content 为模型文本回复（可能为 null），
 *       {@link #getToolCalls()} 携带本轮发起的工具调用（含 id），供历史回放原样发回。</li>
 *   <li>{@link Role#TOOL}：{@link #getToolCallId()} 关联 assistant 的 tool_call
 *       （Spring AI 的 ToolResponse(id, name, responseData) 靠它配对），
 *       {@link #getToolResult()} 为工具执行结果。</li>
 * </ul>
 *
 * <p>循环把"assistant 工具调用轮 + 各 tool 结果轮"按序追加进 history 再传给下一轮；
 * 网关只负责按 role 转成 Spring AI 消息。
 */
public class ChatMessage {

    public enum Role {
        USER,
        ASSISTANT,
        TOOL
    }

    private Role role;

    /**
     * USER / ASSISTANT 的文本内容；TOOL 为 null。
     */
    private String content;

    /**
     * ASSISTANT：本轮发起的工具调用列表（空 = 纯文本回复）。
     */
    private List<ToolCall> toolCalls = List.of();

    /**
     * TOOL：关联的 assistant tool_call id。
     */
    private String toolCallId;

    /**
     * TOOL：工具名。
     */
    private String toolName;

    /**
     * TOOL：工具执行结果（任意 JSON 可序列化对象）。
     */
    private Object toolResult;

    public ChatMessage() {
    }

    private ChatMessage(Role role, String content, List<ToolCall> toolCalls,
                        String toolCallId, String toolName, Object toolResult) {
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls == null ? List.of() : toolCalls;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.toolResult = toolResult;
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, null, null, null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, content, toolCalls, null, null, null);
    }

    public static ChatMessage tool(String toolCallId, String toolName, Object toolResult) {
        return new ChatMessage(Role.TOOL, null, null, toolCallId, toolName, toolResult);
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls == null ? List.of() : toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Object getToolResult() {
        return toolResult;
    }

    public void setToolResult(Object toolResult) {
        this.toolResult = toolResult;
    }
}
