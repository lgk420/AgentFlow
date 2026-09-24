package com.agentflow.api;

import java.util.List;

/**
 * RAG 灌库请求体（T6.4）：要灌入集合的文档列表。
 *
 * <p>每个元素独立嵌入、独立检索。T6.5 起元素可以是纯文本（仅由 API 打 collection 标签）
 * 或 {@link IngestDocument} 对象（自带 metadata，供检索过滤与溯源）——见该类注释。
 */
public class IngestRequest {

    /**
     * 文档列表；元素为字符串或 {text, metadata} 对象。
     */
    private List<IngestDocument> documents;

    public List<IngestDocument> getDocuments() {
        return documents;
    }

    public void setDocuments(List<IngestDocument> documents) {
        this.documents = documents;
    }
}
