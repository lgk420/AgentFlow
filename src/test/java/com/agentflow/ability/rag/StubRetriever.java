package com.agentflow.ability.rag;

import java.util.List;

/**
 * 检索测试替身（T6.3，QA 67 打桩层）——返回固定 chunks、记录最近一次检索参数供断言。
 * 非 bean，仅测试构造用（同 StubLlmGateway 套路）。
 */
public class StubRetriever implements Retriever {

    private final List<RetrievedChunk> chunks;
    private String lastQuery;
    private int lastTopK;
    private String lastCollection;

    public StubRetriever(List<RetrievedChunk> chunks) {
        this.chunks = chunks;
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, int topK, String collection) {
        this.lastQuery = query;
        this.lastTopK = topK;
        this.lastCollection = collection;
        return chunks;
    }

    public List<RetrievedChunk> getChunks() {
        return chunks;
    }

    public String getLastQuery() {
        return lastQuery;
    }

    public int getLastTopK() {
        return lastTopK;
    }

    public String getLastCollection() {
        return lastCollection;
    }
}
