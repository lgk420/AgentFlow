package com.agentflow.core.exec;

import java.util.Map;

/**
 * 节点重试策略（T2.6）：由节点 config 的 {@code retryPolicy: {retries, backoffMs}} 解析。
 *
 * <p>语义：{@code retries} = 初试失败后再重试的次数（{@code retries:2} = 1 次初试 + 2 次重试 = 3 次总尝试）；
 * {@code backoffMs} = 重试间的固定退避。无 {@code retryPolicy} 或字段缺失 → {@code retries=0}（不重试，保持原行为）。
 */
public class RetryPolicy {

    /**
     * 重试次数（单位：次）——初试失败后再重试的次数；0 = 不重试。
     */
    private final int retries;

    /**
     * 每次重试前等待的退避时间（单位：毫秒 ms）——固定退避。
     */
    private final long backoffMs;

    public RetryPolicy(int retries, long backoffMs) {
        this.retries = retries;
        this.backoffMs = backoffMs;
    }

    /**
     * 从节点 config 解析；{@code retryPolicy} 缺失或不是对象时返回"不重试"缺省。
     */
    public static RetryPolicy fromConfig(Map<String, Object> nodeConfig) {
        Object rp = nodeConfig == null ? null : nodeConfig.get("retryPolicy");
        if (!(rp instanceof Map<?, ?> m)) {
            return new RetryPolicy(0, 100);
        }
        return new RetryPolicy(intOf(m, "retries", 0), longOf(m, "backoffMs", 100));
    }

    private static int intOf(Map<?, ?> m, String key, int defaultValue) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : defaultValue;
    }

    private static long longOf(Map<?, ?> m, String key, long defaultValue) {
        Object v = m.get(key);
        return v instanceof Number n ? n.longValue() : defaultValue;
    }

    public int getRetries() {
        return retries;
    }

    public long getBackoffMs() {
        return backoffMs;
    }
}
