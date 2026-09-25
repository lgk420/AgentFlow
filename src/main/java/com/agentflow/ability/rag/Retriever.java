package com.agentflow.ability.rag;

import java.util.List;

/**
 * 检索抽象（T6.1，P6 RAG）。
 *
 * <p>引擎（RagNodeExecutor，T6.3）只依赖本接口，不直接碰 Spring AI VectorStore——换实现、测试注入
 * 假存储都只动这一个 seam（复用 {@code LlmGateway} 同款套路，见 QA 67）。
 * 参数直接对应 RAG 节点 config 的 {@code query / topK / collection}（NodeDefinition#getQuery 等）。
 */
public interface Retriever {

    /**
     * 检索与 query 最相关的 topK 条内容，按相似度从高到低。
     *
     * @param query      查询文本（调用方已做模板解析）
     * @param topK       取前几条
     * @param collection 知识库名；null 表示默认库（不过滤）
     * @return 命中的内容片段，空列表表示无命中
     */
    List<RetrievedChunk> retrieve(String query, int topK, String collection);
}
