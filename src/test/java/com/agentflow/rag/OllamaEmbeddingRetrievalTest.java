package com.agentflow.rag;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T6.2 真实链路验收——真实 bge-m3 嵌入 + 真实 pgvector（QA 67 的"真实那层"，手工验收）。
 *
 * <p>与 {@link VectorStoreRetrieverIntegrationTest}（打桩嵌入、Testcontainers）互补：本用例连
 * 本机 compose 的 postgres（localhost:5432）和 Ollama（localhost:11434），验证嵌入模型真实产出
 * 向量 → pgvector 真实 SQL 检索 → 语义召回。Ollama 不在线时优雅跳过（assumeTrue）。
 *
 * <p>前置：compose 环境已起（bash scripts/dev.sh up --llm）+ 已拉 bge-m3。灌入的文档留在 dev 库，
 * 供 T6.3/T6.4 后续使用。
 */
class OllamaEmbeddingRetrievalTest {

    @Test
    void realBgeM3_retrievesRelevantChunk() {
        assumeTrue(portReachable("localhost", 11434), "Ollama 未启动（localhost:11434），跳过真实嵌入验证");

        OllamaEmbeddingModel embeddingModel = OllamaEmbeddingModel.builder()
                .ollamaApi(OllamaApi.builder().baseUrl("http://localhost:11434").build())
                .defaultOptions(OllamaOptions.builder().model("bge-m3").build())
                .build();

        PgVectorStore store = PgVectorStore.builder(new JdbcTemplate(realPostgres()), embeddingModel)
                .dimensions(1024) // 必须 = bge-m3 维度
                .initializeSchema(true)
                .build();
        store.afterPropertiesSet(); // 非 Spring 管理，手动触发建表

        // 用独立 collection（ittest）隔离：共享 dev 库会积累真实知识库（kb/workout_kb），
        // 不带 collection 过滤时那些更相关的文档会把本测试的文档挤出 top-3，失去确定性
        store.add(List.of(
                Document.builder().text("背部训练：坐姿钢线划船 每组 16 次").metadata("collection", "ittest").build(),
                Document.builder().text("腿部训练：杠铃深蹲 每组 8 次").metadata("collection", "ittest").build(),
                Document.builder().text("渐进超负荷：每周增加 5% 训练重量").metadata("collection", "ittest").build()));

        List<RetrievedChunk> chunks = new VectorStoreRetriever(store).retrieve("怎么练背", 3, "ittest");

        assertThat(chunks).extracting(RetrievedChunk::getContent).contains("背部训练：坐姿钢线划船 每组 16 次");
        assertThat(chunks.get(0).getScore()).isGreaterThan(0);
    }

    /** 本机 compose postgres（docker-compose.yml 默认 agentflow/agentflow）。 */
    private static DataSource realPostgres() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl("jdbc:postgresql://localhost:5432/agentflow");
        ds.setUsername("agentflow");
        ds.setPassword("agentflow");
        return ds;
    }

    private static boolean portReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
