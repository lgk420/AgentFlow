package com.agentflow.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 打印版 LLM 埋点（T4.5）——把每次调用按行打日志：model / 耗时 / token / 工具调用数 / 输入输出。
 *
 * <p>输入输出各截断 {@value #TRUNCATE_LEN} 字（长 prompt/响应不刷屏）；trace 载荷本身保留全量
 * （P8 落库用全量）。P8 换 Redis / TraceSpan 实现时不动网关。
 */
@Component
public class LoggingLlmTracer implements Tracer {

    private static final Logger log = LoggerFactory.getLogger(LoggingLlmTracer.class);

    /**
     * 输入 / 输出日志截断长度。
     */
    private static final int TRUNCATE_LEN = 500;

    @Override
    public void record(LlmCallTrace trace) {
        log.info("[LLM] model={} latency={}ms tokensIn={} tokensOut={} toolCalls={} input={} output={}",
                trace.model() == null ? "-" : trace.model(),
                trace.latencyMs(),
                trace.tokensIn() == null ? "-" : trace.tokensIn(),
                trace.tokensOut() == null ? "-" : trace.tokensOut(),
                trace.toolCallCount(),
                truncate(trace.input()),
                truncate(trace.output()));
    }

    private static String truncate(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.length() <= TRUNCATE_LEN ? s : s.substring(0, TRUNCATE_LEN) + "…";
    }
}
