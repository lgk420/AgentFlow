package com.agentflow.tool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 Spring Bean 的 public 方法为 Agent 工具（T5.2，架构 9.2 注解扫描来源①）。
 *
 * <p>启动时由 {@link AgentToolRegistrar} 扫描所有 Bean，把带本注解的 public 方法反射转换为
 * {@link com.agentflow.tool.ToolDescriptor} 注册进注册中心——方法签名自动推断参数 JSON Schema（浅层 V1）。
 * 重名工具在启动期即抛 {@link com.agentflow.tool.ToolConflictException}，显式暴露冲突。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentTool {

    /**
     * 工具名；缺省用方法名。
     */
    String name() default "";

    /**
     * 给 LLM 看的自然语言描述；缺省用方法名。
     */
    String description() default "";
}
