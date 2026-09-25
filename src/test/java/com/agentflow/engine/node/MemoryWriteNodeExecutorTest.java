package com.agentflow.engine.node;

import com.agentflow.engine.node.MemoryWriteNodeExecutor;

import com.agentflow.engine.node.WorkflowExecutionException;

import java.util.List;
import java.util.Map;

import com.agentflow.ability.llm.ChatMessage;
import com.agentflow.engine.template.TemplateContextFactory;
import com.agentflow.engine.template.TemplateResolver;
import com.agentflow.engine.model.definition.NodeDefinition;
import com.agentflow.engine.model.definition.NodeType;
import com.agentflow.engine.model.state.WorkflowState;
import com.agentflow.ability.memory.MemoryProperties;
import com.agentflow.ability.memory.StubMemoryStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T10.3 MEMORY_WRITE 节点验收测试（走 StubMemoryStore 打桩）。
 *
 * <p>覆盖：写 user + assistant 两条；只配一个时只写一条；**没 sessionId / 记忆关闭时是 no-op**
 * （不写、也不让工作流失败）；两个字段都没配 → 明确报错；模板解析生效。
 */
class MemoryWriteNodeExecutorTest {

    private final StubMemoryStore memoryStore = new StubMemoryStore();
    private final MemoryProperties memoryProperties = new MemoryProperties();
    private final MemoryWriteNodeExecutor executor = new MemoryWriteNodeExecutor(
            memoryStore, memoryProperties, new TemplateResolver(),
            new TemplateContextFactory(memoryStore, memoryProperties));

    private static NodeDefinition memoryNode(Map<String, Object> config) {
        NodeDefinition node = new NodeDefinition();
        node.setType(NodeType.MEMORY_WRITE);
        node.getConfig().putAll(config);
        return node;
    }

    private static WorkflowState state(String sessionId) {
        WorkflowState state = new WorkflowState();
        state.setRunId("run-1");
        state.setSessionId(sessionId);
        state.getInputs().put("userMessage", "我这周练了腿和胸");
        return state;
    }

    @Test
    void writesUserAndAssistant_whenSessionPresent() {
        Object output = executor.execute(memoryNode(Map.of(
                "user", "{{inputs.userMessage}}",
                "assistant", "【报告】本周训练量…")), state("sess-1"));

        List<ChatMessage> history = memoryStore.history("sess-1", 0);
        assertThat(history).extracting(ChatMessage::getContent)
                .containsExactly("我这周练了腿和胸", "【报告】本周训练量…");
        assertThat(history).extracting(ChatMessage::getRole)
                .containsExactly(ChatMessage.Role.USER, ChatMessage.Role.ASSISTANT);
        assertThat(output).isEqualTo(Map.of("written", 2));
        // runId 一并记下，供溯源
        assertThat(memoryStore.getAppendedRunIds()).containsExactly("run-1");
    }

    @Test
    void onlyUserConfigured_writesSingleMessage() {
        executor.execute(memoryNode(Map.of("user", "{{inputs.userMessage}}")), state("sess-2"));

        assertThat(memoryStore.history("sess-2", 0)).hasSize(1);
    }

    /** 无 sessionId = 单次运行：不写记忆，但工作流照常往下走。 */
    @Test
    void noSession_isNoOp() {
        Object output = executor.execute(memoryNode(Map.of(
                "user", "{{inputs.userMessage}}",
                "assistant", "回复")), state(null));

        assertThat(output).isEqualTo(Map.of("written", 0));
        assertThat(memoryStore.getAppendedRunIds()).isEmpty();
    }

    @Test
    void memoryDisabled_isNoOp() {
        memoryProperties.setEnabled(false);

        Object output = executor.execute(memoryNode(Map.of(
                "user", "{{inputs.userMessage}}")), state("sess-3"));

        assertThat(output).isEqualTo(Map.of("written", 0));
        assertThat(memoryStore.getAppendedRunIds()).isEmpty();
    }

    @Test
    void noFieldsConfigured_throwsClearError() {
        assertThatThrownBy(() -> executor.execute(memoryNode(Map.of()), state("sess-4")))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("MEMORY_WRITE");
    }

    /** 模板里的上游节点输出也要能写进去——这才是"把这一轮对话存下来"的典型用法。 */
    @Test
    void resolvesUpstreamNodeOutput() {
        WorkflowState state = state("sess-5");
        state.getNodeOutputs().put("reply",
                new com.agentflow.engine.model.state.NodeOutput("reply", "【报告】深蹲 3×12", null,
                        com.agentflow.engine.model.state.NodeStatus.SUCCEEDED));

        executor.execute(memoryNode(Map.of(
                "user", "{{inputs.userMessage}}",
                "assistant", "{{nodes.reply.output}}")), state);

        assertThat(memoryStore.history("sess-5", 0))
                .extracting(ChatMessage::getContent)
                .containsExactly("我这周练了腿和胸", "【报告】深蹲 3×12");
    }
}
