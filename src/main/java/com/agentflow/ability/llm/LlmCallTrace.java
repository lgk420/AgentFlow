package com.agentflow.ability.llm;

/**
 * 一次 LLM 调用的 trace 载荷（T4.5）——每次 {@link LlmGateway} 调用的统一埋点数据。
 *
 * <p>字段：model（模型名）、latencyMs（耗时）、tokensIn/Out（provider 返回才有，否则 null）、
 * input（本次输入文本，如 userPrompt / systemPrompt）、output（模型回复文本）、
 * toolCallCount（chatWithTools 本轮模型请求的工具调用数）。
 * P8 会把 traceId=runId、父子 span 建树并落库；T4.5 先由 {@link Tracer} 打印。
 */
public record LlmCallTrace(
        String model,
        long latencyMs,
        Integer tokensIn,
        Integer tokensOut,
        String input,
        String output,
        int toolCallCount) {
}
