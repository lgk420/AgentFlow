package com.agentflow.engine.template;

/**
 * 模板解析失败：占位符缺值且无默认 / {{memory...}} 未实现 / 非法默认值字面量等。
 *
 * <p>由 {@link TemplateResolver} 在解析期抛出，消息面向用户（严格模式，坏模板尽早暴露）。
 */
public class TemplateResolutionException extends RuntimeException {

    public TemplateResolutionException(String message) {
        super(message);
    }

    public TemplateResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
