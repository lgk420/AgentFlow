package com.agentflow.core.exec;

import java.util.List;
import java.util.Map;

import com.agentflow.memory.TestTemplateContext;
import com.agentflow.core.dsl.TemplateResolver;
import com.agentflow.core.model.NodeDefinition;
import com.agentflow.core.model.NodeType;
import com.agentflow.core.state.NodeOutput;
import com.agentflow.core.state.NodeStatus;
import com.agentflow.core.state.WorkflowState;
import com.agentflow.rag.RerankProperties;
import com.agentflow.rag.RetrievalProperties;
import com.agentflow.rag.RetrievedChunk;
import com.agentflow.rag.StubReranker;
import com.agentflow.rag.StubRetriever;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T6.3 / T6.7 RagNodeExecutor 验收测试（走 StubRetriever + StubReranker 打桩，QA 67）。
 *
 * <p>覆盖：query 模板解析（inputs / 上游节点输出）；topK/collection 透传；输出 {chunks} 结构；
 * 缺 query 明确报错；空结果写空 chunks 不失败；
 * <b>T6.7 重排</b>：关闭时不调重排且召回 = topK；开启时召回放大到 recall-k；
 * 重排失败降级为向量原序。
 */
class RagNodeExecutorTest {

    private static final List<RetrievedChunk> THREE_CHUNKS = List.of(
            new RetrievedChunk("向量第一名", 0.9),
            new RetrievedChunk("向量第二名", 0.8),
            new RetrievedChunk("向量第三名", 0.7));

    private final StubRetriever retriever = new StubRetriever(List.of(new RetrievedChunk("背部渐进超负荷", 0.9)));
    private final StubReranker reranker = StubReranker.passthrough();
    private final RerankProperties rerankProperties = new RerankProperties();
    private final RetrievalProperties retrievalProperties = new RetrievalProperties();
    private final RagNodeExecutor executor = newExecutor(retriever, reranker, rerankProperties, retrievalProperties);

    private static RagNodeExecutor newExecutor(StubRetriever retriever, StubReranker reranker,
                                               RerankProperties rerankProperties,
                                               RetrievalProperties retrievalProperties) {
        return new RagNodeExecutor(retriever, reranker, rerankProperties, retrievalProperties, new TemplateResolver(), TestTemplateContext.withoutMemory());
    }

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
    private static List<RetrievedChunk> chunksOf(Object output) {
        return (List<RetrievedChunk>) ((Map<String, Object>) output).get("chunks");
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
    void output_writesChunksAndHit() throws Exception {
        Object output = executor.execute(ragNode(Map.of("query", "q")), new WorkflowState());

        assertThat(output).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) output;
        // hit 是 T6.8 加的：DSL 条件边靠它判"要不要走拒答分支"
        assertThat(out).containsOnlyKeys("chunks", "hit");
        assertThat(out.get("hit")).isEqualTo(true);
    }

    @Test
    void missingQuery_throwsClearError() {
        assertThatThrownBy(() -> executor.execute(ragNode(Map.of()), new WorkflowState()))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("query");
    }

    @Test
    void emptyResult_writesEmptyChunks() throws Exception {
        RagNodeExecutor empty = newExecutor(
                new StubRetriever(List.of()), reranker, rerankProperties, retrievalProperties);

        Object output = empty.execute(ragNode(Map.of("query", "q")), new WorkflowState());

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) output;
        assertThat((List<?>) out.get("chunks")).isEmpty();
        // 空结果 = 未命中，下游据此走拒答分支
        assertThat(out.get("hit")).isEqualTo(false);
    }

    @Test
    void queryTemplate_referencesUpstreamNodeOutput() throws Exception {
        WorkflowState state = new WorkflowState();
        state.getNodeOutputs().put("prev",
                new NodeOutput("prev", Map.of("recommendation", "练背推荐"), null, NodeStatus.SUCCEEDED));

        executor.execute(ragNode(Map.of("query", "推荐：{{nodes.prev.output.recommendation}}")), state);

        assertThat(retriever.getLastQuery()).isEqualTo("推荐：练背推荐");
    }

    // ---------- T6.7 重排 ----------

    @Test
    void rerankDisabled_doesNotCallReranker_andRecallsTopKOnly() throws Exception {
        StubRetriever localRetriever = new StubRetriever(THREE_CHUNKS);
        RagNodeExecutor exec = newExecutor(localRetriever, reranker, new RerankProperties(), retrievalProperties);

        List<RetrievedChunk> chunks = chunksOf(exec.execute(ragNode(Map.of("query", "q", "topK", 3)),
                new WorkflowState()));

        // 默认关闭：召回深度就是 topK，且完全没碰重排服务——保证基线行为与加重排前一致
        assertThat(localRetriever.getLastTopK()).isEqualTo(3);
        assertThat(reranker.getCallCount()).isZero();
        assertThat(chunks).extracting(RetrievedChunk::getContent).containsExactly("向量第一名", "向量第二名", "向量第三名");
    }

    @Test
    void rerankEnabled_widensRecall_andReturnsRerankedTopK() throws Exception {
        RerankProperties enabled = new RerankProperties();
        enabled.setEnabled(true);
        enabled.setRecallK(3);
        // 重排把第三名顶到第一：验证返回用的是重排结果，而不是向量原序
        StubReranker promoting = StubReranker.returning(List.of(
                new RetrievedChunk("向量第三名", 0.99), new RetrievedChunk("向量第一名", 0.11)));
        StubRetriever localRetriever = new StubRetriever(THREE_CHUNKS);
        RagNodeExecutor exec = newExecutor(localRetriever, promoting, enabled, retrievalProperties);

        List<RetrievedChunk> chunks = chunksOf(exec.execute(ragNode(Map.of("query", "q", "topK", 2)),
                new WorkflowState()));

        // 召回放大到 max(topK=2, recallK=3) = 3，重排只留 topK=2
        assertThat(localRetriever.getLastTopK()).isEqualTo(3);
        assertThat(promoting.getCallCount()).isEqualTo(1);
        assertThat(promoting.getLastTopN()).isEqualTo(2);
        assertThat(promoting.getLastCandidates()).hasSize(3);
        assertThat(chunks).extracting(RetrievedChunk::getContent).containsExactly("向量第三名", "向量第一名");
    }

    // ---------- T6.8 阈值 / 拒答 ----------

    @Test
    void minScore_dropsLowScoreChunks() throws Exception {
        RetrievalProperties threshold = new RetrievalProperties();
        threshold.getMinScore().setVector(0.85);
        RagNodeExecutor exec = newExecutor(
                new StubRetriever(THREE_CHUNKS), reranker, new RerankProperties(), threshold);

        List<RetrievedChunk> chunks = chunksOf(exec.execute(ragNode(Map.of("query", "q")), new WorkflowState()));

        // 0.9 留下，0.8 / 0.7 被丢弃
        assertThat(chunks).extracting(RetrievedChunk::getContent).containsExactly("向量第一名");
    }

    @Test
    void minScore_dropsEverything_hitIsFalse() throws Exception {
        RetrievalProperties threshold = new RetrievalProperties();
        threshold.getMinScore().setVector(0.95);
        RagNodeExecutor exec = newExecutor(
                new StubRetriever(THREE_CHUNKS), reranker, new RerankProperties(), threshold);

        Map<String, Object> out = outputOf(exec.execute(ragNode(Map.of("query", "q")), new WorkflowState()));

        // 全被过滤掉 = 未命中：下游 DSL 据此走兜底分支，而不是让 LLM 拿空上下文硬编
        assertThat((List<?>) out.get("chunks")).isEmpty();
        assertThat(out.get("hit")).isEqualTo(false);
    }

    @Test
    void minScore_zero_disablesFiltering() throws Exception {
        // 缺省 0.0 = 不启用：行为与加阈值之前完全一致（基线可复现）
        RagNodeExecutor exec = newExecutor(
                new StubRetriever(THREE_CHUNKS), reranker, new RerankProperties(), new RetrievalProperties());

        List<RetrievedChunk> chunks = chunksOf(exec.execute(ragNode(Map.of("query", "q", "topK", 3)),
                new WorkflowState()));

        assertThat(chunks).hasSize(3);
    }

    @Test
    void minScore_usesRerankedThresholdWhenRerankApplied() throws Exception {
        RerankProperties enabled = new RerankProperties();
        enabled.setEnabled(true);
        // 两套阈值量纲不同：向量侧设 0.95（会全杀），重排侧设 0.5（只留高分那条）
        RetrievalProperties threshold = new RetrievalProperties();
        threshold.getMinScore().setVector(0.95);
        threshold.getMinScore().setReranked(0.5);
        StubReranker reranked = StubReranker.returning(List.of(
                new RetrievedChunk("向量第三名", 0.99), new RetrievedChunk("向量第一名", 0.11)));
        RagNodeExecutor exec = newExecutor(new StubRetriever(THREE_CHUNKS), reranked, enabled, threshold);

        List<RetrievedChunk> chunks = chunksOf(exec.execute(ragNode(Map.of("query", "q", "topK", 2)),
                new WorkflowState()));

        // 生效的是重排侧阈值：0.99 过、0.11 被丢——若误用向量阈值 0.95 则两条都该被杀
        assertThat(chunks).extracting(RetrievedChunk::getContent).containsExactly("向量第三名");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> outputOf(Object output) {
        return (Map<String, Object>) output;
    }

    @Test
    void rerankFails_degradesToVectorOrderTruncatedToTopK() throws Exception {
        RerankProperties enabled = new RerankProperties();
        enabled.setEnabled(true);
        StubReranker failing = StubReranker.failing("服务不可用");
        RagNodeExecutor exec = newExecutor(new StubRetriever(THREE_CHUNKS), failing, enabled, retrievalProperties);

        // 重排是增强不是必需：服务挂了应退回向量序，而不是让整个节点失败
        List<RetrievedChunk> chunks = chunksOf(exec.execute(ragNode(Map.of("query", "q", "topK", 2)),
                new WorkflowState()));

        assertThat(failing.getCallCount()).isEqualTo(1);
        assertThat(chunks).extracting(RetrievedChunk::getContent).containsExactly("向量第一名", "向量第二名");
    }
}
