package com.agentflow.api;

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
 * <p><b>collection 标签是检索闭环的关键</b>：T6.1 的 {@link com.agentflow.rag.VectorStoreRetriever}
 * 用 {@code metadata.collection == name} 过滤（FilterExpressionBuilder），灌库不打这个标签检索就过滤不到。
 * 成功返回 200 + {@code {"ingested": N}}；name / documents 为空 → 400（ApiExceptionHandler 统一转）。
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
        List<String> documents = body.getDocuments();
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("documents 不能为空");
        }
        vectorStore.add(documents.stream()
                .map(text -> Document.builder().text(text).metadata("collection", name).build())
                .toList());
        return Map.of("ingested", documents.size());
    }
}
