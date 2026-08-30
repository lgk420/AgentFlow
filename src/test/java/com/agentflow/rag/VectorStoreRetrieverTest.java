package com.agentflow.rag;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6.1 VectorStoreRetriever 单元测试（打桩：假 VectorStore 返回固定 Document，见 QA 67）。
 *
 * <p>验证包装逻辑：query/topK/collection 正确透传成 SearchRequest、Document → RetrievedChunk 映射。
 * 真实 pgvector + Ollama 嵌入的端到端验收留 T6.2（同 LlmGatewayTest 走 Stub 的套路）。
 */
class VectorStoreRetrieverTest {

    /** 记录收到的 SearchRequest、返回固定结果的假 VectorStore。 */
    static class FakeVectorStore implements VectorStore {

        private final List<Document> results;
        final List<SearchRequest> received = new ArrayList<>();

        FakeVectorStore(List<Document> results) {
            this.results = results;
        }

        @Override
        public void add(List<Document> documents) {
            throw new UnsupportedOperationException("测试假存储不支持写入");
        }

        @Override
        public void delete(List<String> ids) {
            throw new UnsupportedOperationException("测试假存储不支持删除");
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
            throw new UnsupportedOperationException("测试假存储不支持删除");
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            received.add(request);
            return results;
        }
    }

    private static Document doc(String text) {
        return Document.builder().text(text).build();
    }

    @Test
    void retrieve_passesQueryAndTopK_withoutCollection() {
        FakeVectorStore store = new FakeVectorStore(List.of(doc("背部训练原则")));
        VectorStoreRetriever retriever = new VectorStoreRetriever(store);

        List<RetrievedChunk> chunks = retriever.retrieve("如何练背", 5, null);

        assertThat(store.received).hasSize(1);
        SearchRequest request = store.received.get(0);
        assertThat(request.getQuery()).isEqualTo("如何练背");
        assertThat(request.getTopK()).isEqualTo(5);
        assertThat(request.hasFilterExpression()).isFalse();
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getContent()).isEqualTo("背部训练原则");
    }

    @Test
    void retrieve_withCollection_setsFilterExpression() {
        FakeVectorStore store = new FakeVectorStore(List.of());
        VectorStoreRetriever retriever = new VectorStoreRetriever(store);

        retriever.retrieve("练背", 3, "kb");

        assertThat(store.received).hasSize(1);
        SearchRequest request = store.received.get(0);
        assertThat(request.hasFilterExpression()).isTrue();
        assertThat(request.getFilterExpression()).isNotNull();
    }

    @Test
    void retrieve_blankCollection_doesNotFilter() {
        FakeVectorStore store = new FakeVectorStore(List.of());
        VectorStoreRetriever retriever = new VectorStoreRetriever(store);

        retriever.retrieve("练背", 3, "  ");

        assertThat(store.received.get(0).hasFilterExpression()).isFalse();
    }

    @Test
    void retrieve_mapsContentAndScore() {
        List<Document> docs = List.of(
                Document.builder().text("背部渐进超负荷").score(0.91).build(),
                Document.builder().text("腿部训练").score(0.72).build());
        VectorStoreRetriever retriever = new VectorStoreRetriever(new FakeVectorStore(docs));

        List<RetrievedChunk> chunks = retriever.retrieve("训练", 2, null);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getContent()).isEqualTo("背部渐进超负荷");
        assertThat(chunks.get(0).getScore()).isEqualTo(0.91);
        assertThat(chunks.get(1).getContent()).isEqualTo("腿部训练");
        assertThat(chunks.get(1).getScore()).isEqualTo(0.72);
    }

    @Test
    void retrieve_nullScore_defaultsToZero() {
        VectorStoreRetriever retriever = new VectorStoreRetriever(new FakeVectorStore(List.of(doc("无分数文档"))));

        List<RetrievedChunk> chunks = retriever.retrieve("x", 1, null);

        assertThat(chunks.get(0).getScore()).isZero();
    }

    @Test
    void retrieve_emptyResult_returnsEmptyList() {
        VectorStoreRetriever retriever = new VectorStoreRetriever(new FakeVectorStore(List.of()));

        assertThat(retriever.retrieve("x", 1, null)).isEmpty();
    }
}
