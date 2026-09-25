package com.agentflow.ability.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 重排配置（T6.7）——绑定 {@code agentflow.rag.rerank.*}。
 *
 * <p><b>默认关闭</b>：{@link #enabled} 缺省 false，此时 RAG 节点的行为与加重排之前完全一致
 * （召回多少就返回多少）。这样基线可复现，对照实验才有意义。
 */
@Component
@ConfigurationProperties(prefix = "agentflow.rag.rerank")
public class RerankProperties {

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
