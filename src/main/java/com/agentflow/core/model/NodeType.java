package com.agentflow.core.model;

/**
 * 节点类型。
 *
 * <p>START/END 是图的结构锚点；LLM / AGENTIC_LOOP / TOOL / RAG 是干活节点。
 * 各类型允许的 config 字段见 docs/DSL使用说明.md 3.3 白名单（GraphValidator 校验）。
 */
public enum NodeType {
    /**
     * 运行入口，注入用户 inputs。图有且仅有一个。
     */
    START,

    /**
     * 运行收敛，产出最终结果。图有且仅有一个。
     */
    END,

    /**
     * 一次模型推理，输出写进 state。config：model / prompt / outputSchema。
     */
    LLM,

    /**
     * 智能体核心：模型↔工具多轮循环，直到给出最终答案（maxIterations 兜底防死循环）。config：model / systemPrompt / tools / maxIterations / outputSchema。
     */
    AGENTIC_LOOP,

    /**
     * 调用注册中心里的一个工具，参数由 DSL 模板绑定。config：tool / inputs。
     */
    TOOL,

    /**
     * 检索向量库，产出上下文 chunks。config：query / topK / collection。
     */
    RAG
}
