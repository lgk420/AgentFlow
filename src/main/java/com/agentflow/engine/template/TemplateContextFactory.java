package com.agentflow.engine.template;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.agentflow.ability.llm.ChatMessage;
import com.agentflow.engine.model.state.WorkflowState;
import com.agentflow.ability.memory.MemoryProperties;
import com.agentflow.ability.memory.MemoryStore;
import org.springframework.stereotype.Component;

/**
 * 模板上下文构建（T10.2）——把运行状态拼成 {@code {{...}}} 占位符能取值的嵌套 Map。
 *
 * <p><b>为什么抽出来</b>：原先 {@code LlmNodeExecutor} / {@code RagNodeExecutor} /
 * {@code AgenticLoopExecutor} 各有一份一模一样的 {@code templateContext()}；加了 memory
 * 命名空间后这份逻辑还要再复制三遍。抽成一个 bean，三处共用，加命名空间也只改这里。
 *
 * <p>命名空间：
 * <ul>
 *   <li>{@code {{inputs.x}}} —— 运行输入</li>
 *   <li>{@code {{nodes.n.output.f}}} —— 上游节点输出</li>
 *   <li>{@code {{memory.history}}} / {@code {{memory.historyText}}} —— 会话记忆（T10.2）</li>
 * </ul>
 *
 * <p><b>不传 sessionId 时 memory 是空命名空间</b>（不是缺 key）：这样
 * {@code {{memory.history | '无'}}} 这类带默认值的模板仍能取到空值而不是报错，
 * 且单次运行（无会话）的行为与加记忆之前完全一致。
 */
@Component
public class TemplateContextFactory {

    private final MemoryStore memoryStore;
    private final MemoryProperties memoryProperties;

    public TemplateContextFactory(MemoryStore memoryStore, MemoryProperties memoryProperties) {
        this.memoryStore = memoryStore;
        this.memoryProperties = memoryProperties;
    }

    /** 拼出本次执行的模板上下文。 */
    public Map<String, Object> contextFor(WorkflowState state) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("inputs", state.getInputs());
        context.put("nodes", state.getNodeOutputs());
        context.put("memory", memoryNamespace(state));
        return context;
    }

    /**
     * memory 命名空间：{@code {history: List, historyText: String}}。
     *
     * <p>取不到会话（没传 sessionId）或记忆被关掉时返回空 map——占位符解出 null，
     * 走模板自己的默认值逻辑（{@code {{x | '默认'}}}）或按缺值报错，与既有语义一致。
     */
    private Map<String, Object> memoryNamespace(WorkflowState state) {
        String sessionId = state.getSessionId();
        if (!memoryProperties.isEnabled() || sessionId == null || sessionId.isBlank()) {
            return Map.of();
        }
        List<ChatMessage> history = memoryStore.history(sessionId, memoryProperties.getHistoryLimit());
        Map<String, Object> namespace = new LinkedHashMap<>();
        // history 给结构化数据（模板会序列化成 JSON）；historyText 给 prompt 友好的文本
        namespace.put("history", history);
        namespace.put("historyText", renderHistory(history));
        return namespace;
    }

    /**
     * 渲染成 prompt 友好的文本（{@code 用户：…\n助手：…}）。
     *
     * <p>直接把 JSON 塞进 prompt 也能用，但可读性差、模型要额外解析一层；
     * 而这段历史的唯一用途就是喂 prompt，所以文本形式更合适。
     */
    private static String renderHistory(List<ChatMessage> history) {
        return history.stream()
                .map(message -> switch (message.getRole()) {
                    case USER -> "用户：" + message.getContent();
                    case ASSISTANT -> "助手：" + message.getContent();
                    case TOOL -> "工具：" + message.getToolResult();
                })
                .collect(Collectors.joining("\n"));
    }
}
