package com.agentflow.ability.llm;

/**
 * LLM 客户端调用失败（网络 / provider 错误 / 结构化输出解析失败等）。
 */
public class LlmClientException extends RuntimeException {

    public LlmClientException(String message) {
        super(message);
    }

    public LlmClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
