package com.agentflow.ability.rag;

import java.util.List;

/**
 * 重排抽象（T6.7，RAG 两阶段检索的第二阶段）。
 *
 * <p><b>为什么需要它</b>：{@link Retriever} 走的向量检索是 bi-encoder——查询与文档
 * <b>各自独立</b>编码成向量后比距离，快（文档向量可离线预算），但两者"从没见过面"，
 * 只能近似语义相似。表现为<b>找得到但排不准</b>：正确块在候选里，却没排在前面。
 *
 * <p>重排用 cross-encoder——查询与文档<b>拼在一起</b>过模型，输出相关性分数，准但慢
 * （每对都要跑一次前向，无法预算），因此只能作用在少量候选上。两者组合即
 * 「召回（快而糙，宁可多捞）→ 重排（慢而精，排出名次）」。
 *
 * <p>执行器只依赖本接口，不直接碰 HTTP——换 provider、换实现、测试注入桩都只动这一个 seam
 * （复用 {@link Retriever} 与 {@code LlmClient} 的同款套路）。
 */
public interface Reranker {

    /**
     * 对候选重排序并取前 topN。
     *
     * @param query      查询文本（调用方已做模板解析）
     * @param candidates 候选块，通常来自向量检索（入参顺序即向量序）
     * @param topN       重排后保留几条
     * @return 重排后的块，按重排分降序；{@code score} 换成重排分，原向量分保留在
     *         {@code metadata.vectorScore}（调试时要看"向量给多少分、重排给多少分"）
     * @throws RuntimeException 重排服务不可用。降级与否由调用方决定：
     *         AgentFlow 执行器降级为向量原序（重排是增强，不该拖垮工作流）；
     *         评测台<b>不降级</b>——异常直接冒泡，避免"重排挂了"被误读成"重排没用"
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN);
}
