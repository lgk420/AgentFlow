package com.agentflow.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 知识库灌库接口（T6.4）——把文档灌进向量库，打 collection 元数据标签。
 *
 * <p><b>collection 标签是检索闭环的关键</b>：T6.1 的 {@link com.agentflow.ability.rag.VectorStoreRetriever}
 * 用 {@code metadata.collection == name} 过滤（FilterExpressionBuilder），灌库不打这个标签检索就过滤不到。
 * 成功返回 200 + {@code {"ingested": N}}；name / documents 为空 → 400（ApiExceptionHandler 统一转）。
 *
 * <p><b>元素形态（T6.5）</b>：{@link IngestDocument} 兼容纯文本与 {text, metadata} 两种写法；
 * 后者的 metadata 原样写入，唯 collection 由路径参数强制覆盖——多知识库共用一张表，
 * 隔离依据不能由请求体自定。
 */
@RestController
@RequestMapping("/api/v1")
public class RagApi {

    private final VectorStore vectorStore;

    public RagApi(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 灌库：把文档列表嵌入并写入向量库，统一标记 collection。
     */
    @PostMapping("/collections/{name}/documents")
    public Map<String, Object> ingest(@PathVariable String name, @RequestBody IngestRequest body) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("collection 名不能为空");
        }
        List<IngestDocument> documents = body.getDocuments();
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("documents 不能为空");
        }
        vectorStore.add(documents.stream()
                .map(document -> toDocument(document, name))
                .toList());
        return Map.of("ingested", documents.size());
    }

    /**
     * 文档 + 路径上的 collection → Spring AI Document。
     * 请求体里同名键被覆盖：collection 以路径为准。
     */
    private static Document toDocument(IngestDocument document, String collection) {
        if (document.getText() == null || document.getText().isBlank()) {
            throw new IllegalArgumentException("documents 元素缺少正文");
        }
        Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
        metadata.put("collection", collection);
        return Document.builder().text(document.getText()).metadata(metadata).build();
    }
}
