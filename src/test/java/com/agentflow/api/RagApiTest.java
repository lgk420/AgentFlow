package com.agentflow.api;

import java.util.List;
import java.util.Map;

import com.agentflow.ability.rag.KeywordEmbeddingModel;
import com.agentflow.ability.rag.dto.RagChunk;
import com.agentflow.ability.rag.retrieval.VectorStoreRagRetriever;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T6.4 灌库 API 全链路测试（Spring 上下文 + Testcontainers pgvector + 打桩嵌入 + MockMvc）。
 *
 * <p>覆盖：POST 灌库 → 200 + ingested；collection 名空 → 400；documents 空 → 400；
 * 灌完用 {@link VectorStoreRagRetriever} bean 检索验证 collection 元数据标签生效（检索闭环）。
 *
 * <p>打桩嵌入覆盖 Ollama：{@code @TestConfiguration} 的 EmbeddingModel bean 在自动配置之前注册，
 * OllamaEmbeddingAutoConfiguration 的 @ConditionalOnMissingBean 回退 → 无 Ollama 依赖、确定性可断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RagApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @DynamicPropertySource
    static void pgProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class StubEmbeddingConfig {

        @Bean
        EmbeddingModel embeddingModel() {
            return new KeywordEmbeddingModel();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VectorStoreRagRetriever retriever;

    @Test
    void ingest_tagsCollectionAndReturnsCount() throws Exception {
        mockMvc.perform(post("/api/v1/collections/kb/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "documents": [
                                    "背部训练：坐姿钢线划船",
                                    "腿部训练：杠铃深蹲"
                                ] }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ingested").value(2));

        // 灌完用真实 VectorStoreRetriever 检索：query 含"背" → 召回背部文档（collection=kb 过滤生效）
        List<RagChunk> chunks = retriever.retrieve("练背", 5, "kb");
        assertThat(chunks).extracting(RagChunk::getContent).contains("背部训练：坐姿钢线划船");
    }

    @Test
    void ingest_blankCollectionName_rejectedAtValidation() {
        // 路径段在真实 HTTP 里不可能为空，isBlank 校验是防御性的（同 ToolApi 校验 name）；
        // MockMvc 不解码 %20，故直接单测校验分支（空 documents → 400 已在上方 HTTP 用例覆盖）
        VectorStore noop = new VectorStore() {
            @Override
            public void add(List<org.springframework.ai.document.Document> documents) {
                throw new AssertionError("校验应在 add 之前失败");
            }

            @Override
            public void delete(List<String> ids) {
            }

            @Override
            public void delete(Filter.Expression filterExpression) {
            }

            @Override
            public List<org.springframework.ai.document.Document> similaritySearch(SearchRequest request) {
                return List.of();
            }
        };
        RagApi api = new RagApi(noop);
        IngestRequest req = new IngestRequest();
        req.setDocuments(List.of(new IngestDocument("x", Map.of())));

        assertThatThrownBy(() -> api.ingest(" ", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collection");
    }

    @Test
    void ingest_emptyDocuments_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/collections/kb/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "documents": [] }
                                """))
                .andExpect(status().isBadRequest());
    }
}
