package com.agentflow.ability.rag.rerank;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentflow.ability.rag.RagProperties;
import com.agentflow.ability.rag.dto.RagChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 基于 Xinference 的重排实现（T6.7）——唯一接重排服务 HTTP API 的地方。
 *
 * <p>调用 {@code POST {baseUrl}/v1/rerank}，请求体 {@code {model, query, documents[]}}，
 * 响应 {@code {results:[{index, relevance_score}]}}，其中 {@code index} 是<b>入参 documents 的下标</b>，
 * 借此把分数映射回原 {@link RagChunk}。
 *
 * <p><b>为什么不用 Ollama</b>：Ollama 只有「生成」「嵌入」两条推理路径，没有 rerank 端点
 * （实测 {@code /api/rerank} 返回 404）；社区上传的 bge-reranker 要么加载即崩、
 * 要么 {@code /api/embed} 返回全零向量。cross-encoder 的输出是分数而非向量，
 * 两条路都不对口，故必须用独立服务。见 {@code docs/重排服务搭建说明.md}。
 */
@Component
public class XinferenceRagReranker implements RagReranker {

    private static final Logger log = LoggerFactory.getLogger(XinferenceRagReranker.class);

    /**
     * Xinference 的 rerank 端点（OpenAI 兼容形态）。
     */
    private static final String RERANK_PATH = "/v1/rerank";

    /**
     * 只存重排那一段配置——本类用不到阈值。
     */
    private final RagProperties.Rerank rerank;

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public XinferenceRagReranker(RagProperties properties, ObjectMapper mapper) {
        this.rerank = properties.getRerank();
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(rerank.getTimeoutSeconds()))
                .build();
    }

    @Override
    public List<RagChunk> rerank(String query, List<RagChunk> ragChunks, int topN) {
        if (ragChunks == null || ragChunks.isEmpty()) {
            return List.of();
        }
        int keep = Math.max(1, Math.min(topN, ragChunks.size()));
        List<Scored> results = score(query, ragChunks);

        List<RagChunk> reranked = new ArrayList<>();
        for (Scored scored : results.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(keep)
                .toList()) {
            reranked.add(withRerankScore(ragChunks.get(scored.index()), scored.score()));
        }
        return reranked;
    }

    /**
     * 调服务打分。请求体只放正文——重排模型只看文本，metadata 对打分没有意义。
     */
    private List<Scored> score(String query, List<RagChunk> ragChunks) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", rerank.getModel());
        body.put("query", query);
        body.put("documents", ragChunks.stream().map(RagChunk::getContent).toList());

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(rerank.getBaseUrl() + RERANK_PATH))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(rerank.getTimeoutSeconds()))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("重排服务返回 HTTP " + response.statusCode()
                        + "（model=" + rerank.getModel() + "）: " + response.body());
            }
            return parseResults(response.body(), ragChunks.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重排服务调用被中断：" + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("重排服务调用失败（" + rerank.getBaseUrl() + "）：" + e.getMessage(), e);
        }
    }

    /**
     * 解析 {@code {results:[{index, relevance_score}]}}。
     *
     * <p>越界的 index 直接跳过而不是让整次调用失败——服务返回多余条目不该毁掉整批结果，
     * 但会记 WARN，因为那通常意味着服务端版本与客户端约定不一致。
     */
    private List<Scored> parseResults(String responseBody, int count) throws IOException {
        JsonNode results = mapper.readTree(responseBody).path("results");
        if (!results.isArray()) {
            throw new IllegalStateException("重排服务响应缺少 results 数组：" + responseBody);
        }
        List<Scored> scored = new ArrayList<>();
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= count) {
                log.warn("重排服务返回越界 index={}（候选数={}），已跳过", index, count);
                continue;
            }
            scored.add(new Scored(index, item.path("relevance_score").asDouble(0.0)));
        }
        return scored;
    }

    /**
     * 换成重排分，原向量分挪进 metadata——调试时最常看的就是"向量给多少、重排给多少"。
     * 不改原对象：候选列表可能被调用方持有。
     */
    private static RagChunk withRerankScore(RagChunk source, double rerankScore) {
        Map<String, Object> metadata = new LinkedHashMap<>(source.getMetadata());
        metadata.put("vectorScore", source.getScore());
        return new RagChunk(source.getContent(), rerankScore, metadata);
    }

    /**
     * 一条打分结果：{@code index} 对应入参 candidates 的下标。
     */
    private record Scored(int index, double score) {
    }
}
