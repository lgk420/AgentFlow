package com.agentflow.core.exec;

import java.util.List;
import java.util.Map;

import com.agentflow.core.dsl.TemplateResolver;
import com.agentflow.core.model.NodeDefinition;
import com.agentflow.core.model.NodeType;
import com.agentflow.core.state.NodeOutput;
import com.agentflow.core.state.NodeStatus;
import com.agentflow.core.state.WorkflowState;
import com.agentflow.rag.RetrievedChunk;
import com.agentflow.rag.StubRetriever;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T6.3 RagNodeExecutor 验收测试（走 StubRetriever 打桩，QA 67）。
 *
 * <p>覆盖：query 模板解析（inputs / 上游节点输出）；topK/collection 透传；输出 {chunks} 结构；
 * 缺 query 明确报错；空结果写空 chunks 不失败。
 */
class RagNodeExecutorTest {

    private final StubRetriever retriever = new StubRetriever(List.of(new RetrievedChunk("背部渐进超负荷", 0.9)));
    private final RagNodeExecutor executor = new RagNodeExecutor(retriever, new TemplateResolver());

    private static NodeDefinition ragNode(Map<String, Object> config) {
        NodeDefinition node = new NodeDefinition();
        node.setType(NodeType.RAG);
        node.getConfig().putAll(config);
        return node;
    }

    private static WorkflowState stateWithInput(String key, Object value) {
        WorkflowState state = new WorkflowState();
        state.getInputs().put(key, value);
        return state;
    }

    @SuppressWarnings("unchecked")
    @Test
    void queryTemplate_isResolvedFromInputs() throws Exception {
        Map<String, Object> output = (Map<String, Object>) executor.execute(
                ragNode(Map.of("query", "用户想：{{inputs.userMessage}}")),
                stateWithInput("userMessage", "练背"));

        assertThat(retriever.getLastQuery()).isEqualTo("用户想：练背");
        List<RetrievedChunk> chunks = (List<RetrievedChunk>) output.get("chunks");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getContent()).isEqualTo("背部渐进超负荷");
        assertThat(chunks.get(0).getScore()).isEqualTo(0.9);
    }

    @Test
    void topKAndCollection_arePassedThrough() throws Exception {
        executor.execute(
                ragNode(Map.of("query", "q", "topK", 5, "collection", "kb")),
                new WorkflowState());

        assertThat(retriever.getLastTopK()).isEqualTo(5);
        assertThat(retriever.getLastCollection()).isEqualTo("kb");
    }

    @Test
    void output_writesChunksUnderKey() throws Exception {
        Object output = executor.execute(ragNode(Map.of("query", "q")), new WorkflowState());

        assertThat(output).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) output;
        assertThat(out).containsOnlyKeys("chunks");
    }

    @Test
    void missingQuery_throwsClearError() {
        assertThatThrownBy(() -> executor.execute(ragNode(Map.of()), new WorkflowState()))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("query");
    }

    @Test
    void emptyResult_writesEmptyChunks() throws Exception {
        RagNodeExecutor empty = new RagNodeExecutor(new StubRetriever(List.of()), new TemplateResolver());

        Object output = empty.execute(ragNode(Map.of("query", "q")), new WorkflowState());

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) output;
        assertThat((List<?>) out.get("chunks")).isEmpty();
    }

    @Test
    void queryTemplate_referencesUpstreamNodeOutput() throws Exception {
        WorkflowState state = new WorkflowState();
        state.getNodeOutputs().put("prev",
                new NodeOutput("prev", Map.of("recommendation", "练背推荐"), null, NodeStatus.SUCCEEDED));

        executor.execute(ragNode(Map.of("query", "推荐：{{nodes.prev.output.recommendation}}")), state);

        assertThat(retriever.getLastQuery()).isEqualTo("推荐：练背推荐");
    }
}
