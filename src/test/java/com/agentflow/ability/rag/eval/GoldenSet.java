package com.agentflow.ability.rag.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 评测集（RAG 检索质量评测，T6.6）。
 *
 * <p>标注的是「<b>答案该从哪一块来</b>」，不是答案本身——本评测台只测检索层，
 * 不测生成质量（那需要 LLM-as-Judge，另属一块）。
 *
 * <p>{@code expect} 写 {@code 文件名#动作名}（即 chunk 的 {@code source#label} 标签）；
 * <b>空数组表示负样本</b>——知识库里没有这个内容，系统应当判为未命中而不是硬答。
 *
 * <p>一个问题的答案若多块都能支撑，就都列上：判定只要求命中其中之一，
 * 标窄了会冤枉检索（它召回了另一块同样能答的，却被记成失败）。
 */
public record GoldenSet(String name, String collection, String description, List<Case> cases) {

    /** 单条评测用例。 */
    public record Case(String id, String type, String question, List<String> expect) {

        /** 负样本：知识库无此内容，期望检索不到。 */
        public boolean isNegative() {
            return expect == null || expect.isEmpty();
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 从 classpath 读评测集，如 {@code "eval/rag-golden-set.json"}。 */
    public static GoldenSet load(String classpathResource) throws IOException {
        try (InputStream in = GoldenSet.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IOException("评测集不存在: " + classpathResource);
            }
            return MAPPER.readValue(in, GoldenSet.class);
        }
    }

    /** 灌库语料：{@code {"documents": [{"text": ..., "metadata": {...}}]}}。 */
    public record Corpus(List<Document> documents) {

        /** 单篇：正文 + 标签。 */
        public record Document(String text, java.util.Map<String, Object> metadata) {

            /** {@code 文件名#动作名}——评测集用它标识一个 chunk。 */
            public String label() {
                return metadata.get("source") + "#" + metadata.get("label");
            }
        }

        public static Corpus load(String classpathResource) throws IOException {
            try (InputStream in = GoldenSet.class.getClassLoader().getResourceAsStream(classpathResource)) {
                if (in == null) {
                    throw new IOException("语料不存在: " + classpathResource);
                }
                return MAPPER.readValue(in, Corpus.class);
            }
        }
    }
}
