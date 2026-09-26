package com.agentflow.ability.rag;

import com.agentflow.ability.rag.dto.RagChunk;
import com.agentflow.ability.rag.retrieval.RagRetriever;

import java.util.List;

/**
 * 检索测试替身（T6.3，QA 67 打桩层）——返回固定 chunks、记录最近一次检索参数供断言。
 * 非 bean，仅测试构造用（同 StubLlmClient 套路）。
 */
public class StubRagRetriever implements RagRetriever {

    private final List<RagChunk> chunks;
    private String lastQuery;
    private int lastTopK;
    private String lastCollection;

    public StubRagRetriever(List<RagChunk> chunks) {
        this.chunks = chunks;
    }

    @Override
    public List<RagChunk> retrieve(String query, int topK, String collection) {
        this.lastQuery = query;
        this.lastTopK = topK;
        this.lastCollection = collection;
        return chunks;
    }

    public List<RagChunk> getChunks() {
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
