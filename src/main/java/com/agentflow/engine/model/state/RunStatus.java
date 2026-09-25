package com.agentflow.engine.model.state;

/**
 * 一次运行的总体状态（架构 6.2 状态机）。
 *
 * <p>PAUSED 为 P3 checkpoint/resume 预留，P2 只用 RUNNING / SUCCEEDED / FAILED。
 */
public enum RunStatus {
    /**
     * 正在执行。
     */
    RUNNING,

    /**
     * 已到达 END，正常结束。
     */
    SUCCEEDED,

    /**
     * 节点重试耗尽或其他不可恢复错误，运行失败。
     */
    FAILED,

    /**
     * 暂停（P3 resume 预留）。
     */
    PAUSED
}
