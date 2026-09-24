package com.agentflow.rag;

import java.util.Map;

/**
 * 一次检索命中——轻量类型，不泄漏 Spring AI 的 {@code Document}（对齐 LlmGateway 的 ChatResult/ToolCall）。
 * 供 RAG 节点写 {@code {chunks}} 到 state、下游模板 {@code {{nodes.x.output.chunks}}} 引用。
 *
 * <p><b>metadata（T6.5）</b>：从向量库带回来的标签（灌库时写入）。检索层靠它标识命中来源——
 * 正文是可变的文本，只有标签能稳定回答"这段来自哪个知识单元"，评测与引用溯源都依赖它。
 * 这里存整个 map 而不拆成 source/label 字段：RAG 节点对具体知识库的 frontmatter 结构一无所知，
 * 换一个知识库标签就全变了。
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

    /**
     * 灌库时写入的标签；无标签时为空 map（不为 null）。
     */
    private Map<String, Object> metadata = Map.of();

    public RetrievedChunk() {
    }

    public RetrievedChunk(String content, double score) {
        this(content, score, Map.of());
    }

    public RetrievedChunk(String content, double score, Map<String, Object> metadata) {
        this.content = content;
        this.score = score;
        this.metadata = metadata == null ? Map.of() : metadata;
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

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? Map.of() : metadata;
    }
}
