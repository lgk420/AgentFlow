package com.agentflow.core.dsl;

/**
 * DSL 解析失败：JSON 语法错 / 枚举非法 / 未知字段等。
 *
 * <p>消息面向用户，对应 DSL使用说明 7 "常见错误"（如 {@code JSON 解析失败：...}、{@code 未知的 type：FOO}），
 * 由 {@link GraphParser} 把底层 Jackson 异常包装而来。
 */
public class WorkflowParseException extends RuntimeException {

    public WorkflowParseException(String message) {
        super(message);
    }

    public WorkflowParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
