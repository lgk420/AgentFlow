package com.agentflow.rag;

/**
 * 一次检索命中——轻量类型，不泄漏 Spring AI 的 {@code Document}（对齐 LlmGateway 的 ChatResult/ToolCall）。
 * 供 RAG 节点写 {@code {chunks}} 到 state、下游模板 {@code {{nodes.x.output.chunks}}} 引用。
 */
public class RetrievedChunk {

    /**
     * 文档正文。
     */
    private String content;

    /**
     * 相似度分数；检索未打分时为 0。
     */
    private double score;

    public RetrievedChunk() {
    }

    public RetrievedChunk(String content, double score) {
        this.content = content;
        this.score = score;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }
}
