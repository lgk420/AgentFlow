package com.agentflow.engine.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.agentflow.ability.llm.ChatMessage;
import com.agentflow.engine.template.TemplateContextFactory;
import com.agentflow.engine.template.TemplateResolver;
import com.agentflow.engine.model.definition.NodeDefinition;
import com.agentflow.engine.model.definition.NodeType;
import com.agentflow.engine.model.state.WorkflowState;
import com.agentflow.ability.memory.MemoryProperties;
import com.agentflow.ability.memory.MemoryStore;
import org.springframework.stereotype.Component;

/**
 * MEMORY_WRITE 节点执行器（T10.3）——把一轮对话写进会话记忆。
 *
 * <p>config 两个字段都是**模板**，<b>至少给一个</b>（执行器自守，同 RAG 节点守 query 的做法）：
 * <pre>
 *   { "type": "MEMORY_WRITE",
 *     "user":      "{{inputs.userMessage}}",
 *     "assistant": "{{nodes.reply.output}}" }
 * </pre>
 *
 * <p><b>sessionId 取自运行状态</b>，不在节点上配——它是运行级概念（见 {@link WorkflowState#getSessionId()}）。
 * <b>没传 sessionId 或记忆被关掉时本节点是 no-op</b>（返回 {@code {written: 0}}），
 * 这样同一份工作流既能用于有记忆的对话，也能跑无记忆的单次调用。
 *
 * <p>并发契约（QA 39）：只读 state、返回结果，不写 state——记忆是外部存储，不属于运行状态。
 */
@Component
public class MemoryWriteNodeExecutor implements NodeExecutor {

    private final MemoryStore memoryStore;
    private final MemoryProperties memoryProperties;
    private final TemplateResolver templateResolver;
    private final TemplateContextFactory templateContextFactory;

    public MemoryWriteNodeExecutor(MemoryStore memoryStore, MemoryProperties memoryProperties,
                                   TemplateResolver templateResolver,
                                   TemplateContextFactory templateContextFactory) {
        this.memoryStore = memoryStore;
        this.memoryProperties = memoryProperties;
        this.templateResolver = templateResolver;
        this.templateContextFactory = templateContextFactory;
    }

    @Override
    public NodeType type() {
        return NodeType.MEMORY_WRITE;
    }

    @Override
    public Object execute(NodeDefinition node, WorkflowState state) {
        String sessionId = state.getSessionId();
        if (!memoryProperties.isEnabled() || sessionId == null || sessionId.isBlank()) {
            // 无会话的单次运行：不写记忆，但也不让工作流失败
            return Map.of("written", 0);
        }

        Map<String, Object> context = templateContextFactory.contextFor(state);
        List<ChatMessage> messages = new ArrayList<>();
        String user = node.getUserMessage();
        if (user != null && !user.isBlank()) {
            messages.add(ChatMessage.user(templateResolver.resolve(user, context)));
        }
        String assistant = node.getAssistantReply();
        if (assistant != null && !assistant.isBlank()) {
            messages.add(ChatMessage.assistant(templateResolver.resolve(assistant, context), List.of()));
        }

        if (messages.isEmpty()) {
            throw new WorkflowExecutionException("MEMORY_WRITE 节点未配置 user / assistant：" + node.getId());
        }

        memoryStore.append(sessionId, state.getRunId(), messages);
        return Map.of("written", messages.size());
    }
}
