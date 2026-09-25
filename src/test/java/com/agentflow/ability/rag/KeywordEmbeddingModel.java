package com.agentflow.ability.rag;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 关键词→固定向量的打桩嵌入（T6.2/6.4，QA 67 打桩层）——背/腿/胸 各占一个坐标，维度 1024 对齐 pgvector 列。
 *
 * <p>确定性、无 Ollama 依赖，供集成/API 测试覆盖"打桩 EmbeddingModel 覆盖真实嵌入"的场景：
 * {@code VectorStoreRetrieverIntegrationTest} 与 {@code RagApiTest} 复用。
 */
public class KeywordEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 1024;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        List<String> instructions = request.getInstructions();
        for (int i = 0; i < instructions.size(); i++) {
            embeddings.add(new Embedding(vectorFor(instructions.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return vectorFor(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private static float[] vectorFor(String text) {
        float[] v = new float[DIMENSIONS];
        if (text.contains("背")) {
            v[0] = 1f;
        }
        if (text.contains("腿")) {
            v[1] = 1f;
        }
        if (text.contains("胸")) {
            v[2] = 1f;
        }
        return v;
    }
}
