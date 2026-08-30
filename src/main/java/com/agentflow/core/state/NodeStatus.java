package com.agentflow.core.state;

/**
 * 节点执行结果状态。
 *
 * <p>nodeOutputs 只记录<b>执行过</b>的节点（条件分支没走到的节点不在其中），
 * 所以不需要 PENDING；未执行节点在调度层就绪集里管理，不进 state。
 */
public enum NodeStatus {
    /**
     * 节点执行成功，产出 output。
     */
    SUCCEEDED,

    /**
     * 节点执行失败（重试耗尽后），error 记录原因。
     */
    FAILED
}
