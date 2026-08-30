package com.agentflow.rag;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6.2 VectorStoreRetriever 集成测试——真实 pgvector 链路（打桩 EmbeddingModel，QA 67 的"自动化那层"）。
 *
 * <p>验证：灌入文档 → PgVectorStore（真实 pgvector SQL + HNSW）→ retrieve 召回相关 chunk。
 * 嵌入用 {@link KeywordEmbeddingModel} 打桩（关键词→固定向量），不连 Ollama，确定性可断言。
 *
 * <p>前置：本机 DOCKER_HOST 可达（同 RedisContainerBaselineTest，Testcontainers 拉 pgvector/pgvector:pg16）。
 * 真实 Ollama + bge-m3 的端到端验收见 {@link OllamaEmbeddingRetrievalTest}。
 */
@Testcontainers
class VectorStoreRetrieverIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static DataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    @Test
    void addThenRetrieve_returnsRelevantChunks() {
        PgVectorStore store = PgVectorStore.builder(new JdbcTemplate(dataSource()), new KeywordEmbeddingModel())
                .dimensions(1024)
                .initializeSchema(true)
                .build();
        store.afterPropertiesSet(); // 非 Spring 管理，手动触发建表
        VectorStoreRetriever retriever = new VectorStoreRetriever(store);

        store.add(List.of(
                Document.builder().text("背部训练：坐姿钢线划船").build(),
                Document.builder().text("腿部训练：深蹲").build()));

        List<RetrievedChunk> chunks = retriever.retrieve("练背", 2, null);

        assertThat(chunks).extracting(RetrievedChunk::getContent).contains("背部训练：坐姿钢线划船");
        assertThat(chunks.get(0).getScore()).isGreaterThan(0);
    }
}
