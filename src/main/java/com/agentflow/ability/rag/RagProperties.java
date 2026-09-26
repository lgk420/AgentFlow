package com.agentflow.ability.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 配置（T6.7 / T6.8）——绑定 {@code agentflow.rag.*}。
 *
 * <p>两块，各自对应配置树上的一个子节点：
 * <ul>
 *   <li>{@link MinScore} —— 相似度阈值：决定「命中」判定；</li>
 *   <li>{@link Rerank} —— 重排服务：开关 / 地址 / 模型 / 候选池 / 超时。</li>
 * </ul>
 *
 * <p><b>为什么合成一个类</b>：{@code agentflow.rag.rerank} 本来就是 {@code agentflow.rag} 的子路径，
 * 两块同在一棵树下。合成后<b>类名与配置路径一一对应</b>（看到 {@code RagProperties} 就知道去 yml 的
 * {@code agentflow.rag} 一节），消费方也少一个 bean 要注入。两块用嵌套类分开，不会混成一坨。
 */
@Component
@ConfigurationProperties(prefix = "agentflow.rag")
public class RagProperties {

    private final MinScore minScore = new MinScore();

    private final Rerank rerank = new Rerank();

    public MinScore getMinScore() {
        return minScore;
    }

    public Rerank getRerank() {
        return rerank;
    }

    /**
     * 检索结果过滤（T6.8）——按相似度阈值决定「命中」。
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
     * 所以开重排时用 {@link #reranked}，否则用 {@link #vector}。
     *
     * <p>缺省都是 <b>0.0 = 不启用过滤</b>，此时行为与加重排/阈值之前完全一致（基线可复现）。
     */
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

    /**
     * 重排配置（T6.7）。
     *
     * <p><b>默认关闭</b>：{@link #enabled} 缺省 false，此时 RAG 节点的行为与加重排之前完全一致
     * （召回多少就返回多少）。这样基线可复现，对照实验才有意义。
     */
    public static class Rerank {

        /**
         * 是否启用重排。false 时 RAG 节点不放大召回、不调重排服务，行为与加重排前一致。
         */
        private boolean enabled = false;

        /**
         * Xinference 服务地址（compose 的 reranker 服务，只绑本机回环）。
         */
        private String baseUrl = "http://localhost:9997";

        /**
         * 重排模型名，对应 {@code xinference launch} 返回并注册的 model_uid。
         */
        private String model = "bge-reranker-v2-m3";

        /**
         * 召回条数——交给重排的候选池大小（最终返回仍是节点自己的 topK）。
         *
         * <p>取值的取舍：太小（接近 topK）重排没得挑，正确的若排在池外就够不着；
         * 太大则等于把大半个库递过去，重排"挑对的"变得过于容易，收益虚高。
         * 实际生效值 = max(节点 topK, 本值)。
         */
        private int recallK = 3;

        /**
         * 单次重排调用超时（秒）。
         *
         * <p>缺省 30 而非 10：CPU 推理下单次重排实测 P99 已达 8.7s，10s 只剩 1 秒余量；
         * 更关键的是<b>冷启动</b>——模型长期未用会被卸载，首次调用要先把它加载回内存，
         * 在 CPU 上轻易超过 10s（实测触发过一次探活失败）。宁可等久一点，也不要误判成"服务不可用"。
         */
        private int timeoutSeconds = 30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getRecallK() {
            return recallK;
        }

        public void setRecallK(int recallK) {
            this.recallK = recallK;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
