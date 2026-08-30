package com.agentflow.api;

import java.util.List;

/**
 * RAG 灌库请求体（T6.4）：要灌入集合的文档文本列表。
 *
 * <p>V1 只收纯文本（每个元素独立嵌入、独立检索）；metadata 由 API 统一打 collection 标签（路径上的集合名），
 * 不开放自定义 metadata——够用且简单（YAGNI）。
 */
public class IngestRequest {

    /**
     * 文档正文列表。
     */
    private List<String> documents;

    public List<String> getDocuments() {
        return documents;
    }

    public void setDocuments(List<String> documents) {
        this.documents = documents;
    }
}
