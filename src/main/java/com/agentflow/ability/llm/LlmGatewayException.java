package com.agentflow.ability.llm;

/**
 * LLM 网关调用失败（网络 / provider 错误 / 结构化输出解析失败等）。
 */
public class LlmGatewayException extends RuntimeException {

    public LlmGatewayException(String message) {
        super(message);
    }

    public LlmGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
