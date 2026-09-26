package com.agentflow.ability.rag.retrieval;

import java.util.List;

import com.agentflow.ability.rag.dto.RagChunk;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

/**
 * 基于 Spring AI {@link VectorStore} 的检索实现（T6.1，P6 RAG）。
 *
 * <p>唯一接 Spring AI 检索 API 的地方：query/topK/collection → {@link SearchRequest} →
 * {@link VectorStore#similaritySearch} → {@code List<Document>} → {@code List<RetrievedChunk>}。
 *
 * <p><b>bean 注册（T6.2）</b>：T6.1 阶段 {@code VectorStore} 尚不存在故不注册；T6.2 引入 pgvector
 * starter 后 bean 由自动配置提供，本类加 {@code @Component} 注册。构造器注入在 bean 实例化期解析
 * （晚于自动配置注册），因此无需任何条件注解——T6.1 曾考虑 {@code @ConditionalOnBean}，验证发现它
 * 看不到自动配置注册的 bean 会静默失效（记录于任务拆解 T6.1 踩坑）。
 */
@Component
public class VectorStoreRagRetriever implements RagRetriever {

    private final VectorStore vectorStore;

    public VectorStoreRagRetriever(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public List<RagChunk> retrieve(String query, int topK, String collection) {
        SearchRequest.Builder builder = SearchRequest.builder().query(query).topK(topK);
        if (collection != null && !collection.isBlank()) {
            builder.filterExpression(new FilterExpressionBuilder().eq("collection", collection).build());
        }
        return vectorStore.similaritySearch(builder.build()).stream()
                .map(document -> new RagChunk(
                        document.getText(),
                        document.getScore() == null ? 0.0 : document.getScore(),
                        document.getMetadata()))
                .toList();
    }
}
