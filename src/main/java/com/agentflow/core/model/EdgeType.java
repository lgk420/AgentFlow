package com.agentflow.core.model;

/**
 * 边类型（路由语义）。
 *
 * <p>STATIC 固定 / CONDITIONAL 规则 / LLM_DYNAMIC 模型决策，见 docs/DSL使用说明.md 4.2。
 */
public enum EdgeType {
    /**
     * 无条件通过，上游节点完成即走。
     */
    STATIC,

    /**
     * 规则分流：多个候选边各带 SpEL condition，按声明顺序首个为真者生效。
     */
    CONDITIONAL,

    /**
     * 模型决策：把候选边的 label/description 交给模型，模型返回 {nextNode} 选一条。
     */
    LLM_DYNAMIC
}
