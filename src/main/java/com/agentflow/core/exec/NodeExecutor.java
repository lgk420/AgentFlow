package com.agentflow.core.exec;

import com.agentflow.core.model.NodeDefinition;
import com.agentflow.core.model.NodeType;
import com.agentflow.core.state.WorkflowState;

/**
 * 节点执行器：按 {@link NodeType} 分发的执行单元。
 *
 * <p>WorkflowExecutor 通过 {@link #type()} 建 Map 分发；真实执行器（LLM/工具/RAG/Loop）在 P4~P6 逐步
 * 替换当前 Stub 实现（调度逻辑不变，见 QA 36）。
 *
 * <p><b>并发契约（QA 39）</b>：实现<b>只读 state、返回结果、绝不写 state</b>——
 * 写 nodeOutputs 由调度器批后统一执行，并发执行期不允许修改 state。
 */
public interface NodeExecutor {

    /**
     * 本执行器负责的节点类型。
     */
    NodeType type();

    /**
     * 执行节点，返回节点输出（Object）；失败抛异常，由调度器包装成 FAILED。
     *
     * @param state 运行状态，只读——供引用上游节点输出（真实执行器模板解析用）
     */
    Object execute(NodeDefinition node, WorkflowState state);
}
