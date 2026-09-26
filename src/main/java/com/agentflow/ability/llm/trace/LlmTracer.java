package com.agentflow.ability.llm.trace;

/**
 * LLM 调用埋点出口（T4.5，P8 trace 预留）——{@link SpringAiLlmClient} 每次调用后把
 * {@link LlmCallTrace} 交到这里。T4.5 用 {@link LogLlmTracer} 打印；P8 换 Redis / TraceSpan 实现。
 */
public interface LlmTracer {

    /**
     * 记录一次 LLM 调用。
     */
    void record(LlmCallTrace trace);
}
