package com.agentflow.core.exec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentflow.core.dsl.TemplateResolver;
import com.agentflow.core.model.NodeDefinition;
import com.agentflow.core.model.NodeType;
import com.agentflow.core.state.WorkflowState;
import com.agentflow.rag.RerankProperties;
import com.agentflow.rag.Reranker;
import com.agentflow.rag.RetrievalProperties;
import com.agentflow.rag.Retriever;
import com.agentflow.rag.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * RAG 节点执行器（T6.3 检索，T6.7 重排，T6.8 阈值/拒答）——检索向量库，产出上下文 chunks 写 state。
 *
 * <p>流程：query 模板解析 → <b>召回</b>（{@link Retriever}）→ 可选<b>重排</b>（{@link Reranker}）
 * → <b>相似度阈值过滤</b> → 返回 {@code {chunks: [...], hit: 布尔}}。
 *
 * <p><b>两阶段与召回放大（T6.7）</b>：向量检索是 bi-encoder，快但"排不准"；重排是 cross-encoder，
 * 准但只能作用在少量候选上。因此启用重排时会先把召回放大到 {@code rerank.recall-k}，
 * 给重排"够得着正确块"的机会——只召回 topK 条的话，排在池外的正确块重排根本看不到。
 * 未启用时召回就是 topK，行为与加重排之前完全一致（基线可复现）。
 *
 * <p><b>命中判定（T6.8）</b>：过滤掉低于阈值的块后，若一条不剩则 {@code hit=false}。
 * 下游 DSL 用 CONDITIONAL 边判 {@code {{nodes.x.output.hit}} == false} 走拒答兜底分支，
 * <b>而不是让 LLM 拿着空上下文硬编</b>。阈值分向量/重排两套（量纲不同，见 {@link RetrievalProperties}）。
 *
 * <p>query 必填由执行器自守（GraphValidator 只校验白名单不校验必填，QA 27——同
 * ToolNodeExecutor 校验 tool）；检索空结果返回空 chunks + hit=false 不失败。
 * 失败（检索抛错 / 模板缺 key）由 WorkflowExecutor 统一包装为节点 FAILED（T2.6）。
 *
 * <p>并发契约（QA 39）：只读 state、返回结果，不写 state。
 */
@Component
public class RagNodeExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(RagNodeExecutor.class);

    private final Retriever retriever;
    private final Reranker reranker;
    private final RerankProperties rerankProperties;
    private final RetrievalProperties retrievalProperties;
    private final TemplateResolver templateResolver;

    public RagNodeExecutor(Retriever retriever, Reranker reranker, RerankProperties rerankProperties,
                           RetrievalProperties retrievalProperties, TemplateResolver templateResolver) {
        this.retriever = retriever;
        this.reranker = reranker;
        this.rerankProperties = rerankProperties;
        this.retrievalProperties = retrievalProperties;
        this.templateResolver = templateResolver;
    }

    @Override
    public NodeType type() {
        return NodeType.RAG;
    }

    @Override
    public Object execute(NodeDefinition node, WorkflowState state) {
        String query = node.getQuery();
        if (query == null || query.isBlank()) {
            throw new WorkflowExecutionException("RAG 节点缺少 query 配置：" + node.getId());
        }
        String resolved = templateResolver.resolve(query, templateContext(state));

        int topK = node.getTopK();
        boolean rerankEnabled = rerankProperties.isEnabled();
        int recallK = rerankEnabled ? Math.max(topK, rerankProperties.getRecallK()) : topK;

        List<RetrievedChunk> chunks = retriever.retrieve(resolved, recallK, node.getCollection());

        boolean reranked = false;
        if (rerankEnabled && chunks.size() > 1) {
            List<RetrievedChunk> rerankedChunks = rerankOrNull(node.getId(), resolved, chunks, topK);
            if (rerankedChunks == null) {
                chunks = truncate(chunks, topK);
            } else {
                chunks = rerankedChunks;
                reranked = true;
            }
        }

        List<RetrievedChunk> hits = filterByMinScore(chunks, reranked);
        return Map.of("chunks", hits, "hit", !hits.isEmpty());
    }

    /**
     * 重排，失败返回 null 交由调用方降级。
     *
     * <p><b>为什么降级而不是让节点失败</b>：重排是检索质量的<b>增强</b>，不是工作流的必需环节。
     * 重排服务不可用时退回向量序，下游照常拿到一份可用的上下文，比整个节点 FAILED 更合理。
     *
     * <p>注意评测台<b>不走这条路径</b>——它直接调 {@link Reranker}，异常直接冒泡让测试失败。
     * 否则"重排服务挂了"会被静默记成"重排没有提升"，那个数字就废了。
     */
    private List<RetrievedChunk> rerankOrNull(String nodeId, String query, List<RetrievedChunk> chunks, int topK) {
        try {
            return reranker.rerank(query, chunks, topK);
        } catch (RuntimeException e) {
            log.warn("RAG 节点 {} 重排失败，降级为向量原序：{}", nodeId, e.getMessage());
            return null;
        }
    }

    private static List<RetrievedChunk> truncate(List<RetrievedChunk> chunks, int topK) {
        return chunks.size() > topK ? chunks.subList(0, topK) : chunks;
    }

    /**
     * 相似度阈值过滤。
     *
     * <p>用哪个阈值取决于分数的来源：<b>重排分与向量分不是一个量纲</b>
     * （重排后 score 是 cross-encoder 相关性分，未重排时是向量余弦相似度），
     * 同一个数不可能同时适用两条路径——详见 {@link RetrievalProperties}。
     *
     * <p>阈值为 0 表示不启用，此时原样返回，行为与加阈值之前完全一致。
     */
    private List<RetrievedChunk> filterByMinScore(List<RetrievedChunk> chunks, boolean reranked) {
        double minScore = reranked
                ? retrievalProperties.getMinScore().getReranked()
                : retrievalProperties.getMinScore().getVector();
        if (minScore <= 0.0) {
            return chunks;
        }
        return chunks.stream().filter(chunk -> chunk.getScore() >= minScore).toList();
    }

    private static Map<String, Object> templateContext(WorkflowState state) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("inputs", state.getInputs());
        ctx.put("nodes", state.getNodeOutputs());
        return ctx;
    }
}
