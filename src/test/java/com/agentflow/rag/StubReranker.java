package com.agentflow.rag;

import java.util.List;

/**
 * 重排测试替身（T6.7，QA 67 打桩层）——可配置为"原序透传""返回指定结果"或"抛异常"，
 * 并记录最近一次调用参数供断言。非 bean，仅测试构造用（同 StubRetriever / StubLlmGateway 套路）。
 */
public class StubReranker implements Reranker {

    private final List<RetrievedChunk> result;
    private final RuntimeException failure;

    private String lastQuery;
    private List<RetrievedChunk> lastCandidates;
    private int lastTopN;
    private int callCount;

    private StubReranker(List<RetrievedChunk> result, RuntimeException failure) {
        this.result = result;
        this.failure = failure;
    }

    /** 原序透传前 topN——模拟"重排不改变顺序"，用于验证管道接线而非重排效果。 */
    public static StubReranker passthrough() {
        return new StubReranker(null, null);
    }

    /** 固定返回给定结果。 */
    public static StubReranker returning(List<RetrievedChunk> result) {
        return new StubReranker(result, null);
    }

    /** 每次调用都抛异常——用于验证执行器的降级路径。 */
    public static StubReranker failing(String message) {
        return new StubReranker(null, new IllegalStateException(message));
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        this.lastQuery = query;
        this.lastCandidates = candidates;
        this.lastTopN = topN;
        this.callCount++;
        if (failure != null) {
            throw failure;
        }
        if (result != null) {
            return result;
        }
        return candidates.stream().limit(Math.max(1, topN)).toList();
    }

    public String getLastQuery() {
        return lastQuery;
    }

    public List<RetrievedChunk> getLastCandidates() {
        return lastCandidates;
    }

    public int getLastTopN() {
        return lastTopN;
    }

    public int getCallCount() {
        return callCount;
    }
}
