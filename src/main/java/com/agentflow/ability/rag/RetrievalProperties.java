package com.agentflow.ability.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 检索结果过滤配置（T6.8）——绑定 {@code agentflow.rag.*}，目前只有相似度阈值。
 *
 * <p><b>命中的判定</b>：RAG 节点把低于阈值的块丢掉，剩下的为空即视为「未命中」，
 * 输出 {@code hit=false}，供 DSL 的 CONDITIONAL 边走拒答兜底分支——
 * 而不是让下游 LLM 拿着空上下文硬编。
 *
 * <p><b>为什么是两个阈值而不是一个</b>：两条路径的分数<b>量纲不同</b>，同一个数不可能同时适用。
 * 实测（42 块语料 / 50 条评测集）：
 * <table border="1">
 *   <tr><th>路径</th><th>正样本 top1 均分</th><th>负样本 top1 均分</th><th>可分性</th></tr>
 *   <tr><td>向量余弦相似度</td><td>0.695</td><td>0.555</td><td>差 0.14，两团重叠，阈值难设</td></tr>
 *   <tr><td>重排相关性分</td><td>0.784</td><td>0.007</td><td>差 0.78，中间大片空白，阈值好设</td></tr>
 * </table>
 * 所以开重排时用 {@link MinScore#getReranked()}，否则用 {@link MinScore#getVector()}。
 *
 * <p>缺省都是 <b>0.0 = 不启用过滤</b>，此时行为与加重排/阈值之前完全一致（基线可复现）。
 */
@Component
@ConfigurationProperties(prefix = "agentflow.rag")
public class RetrievalProperties {

    private final MinScore minScore = new MinScore();

    public MinScore getMinScore() {
        return minScore;
    }

    /** 相似度阈值：低于它的块被丢弃；全被丢弃即判「未命中」。0 = 不启用。 */
    public static class MinScore {

        /**
         * 向量余弦相似度的阈值——未启用重排（或重排降级回退）时生效。
         */
        private double vector = 0.0;

        /**
         * cross-encoder 相关性分的阈值——重排实际生效时生效。
         */
        private double reranked = 0.0;

        public double getVector() {
            return vector;
        }

        public void setVector(double vector) {
            this.vector = vector;
        }

        public double getReranked() {
            return reranked;
        }

        public void setReranked(double reranked) {
            this.reranked = reranked;
        }
    }
}
