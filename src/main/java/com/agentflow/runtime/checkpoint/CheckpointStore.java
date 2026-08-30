package com.agentflow.runtime.checkpoint;

import com.agentflow.core.state.WorkflowState;

/**
 * Checkpoint 存储：一次运行的可序列化快照的存取。
 *
 * <p>P2 用 {@link InMemoryCheckpointStore}（内存版）；P3 换 Redis 实现（JSON 快照 + version CAS）。
 */
public interface CheckpointStore {

    /**
     * 保存状态快照（实现内部负责拷贝，防调用方引用污染）。
     */
    void save(WorkflowState state);

    /**
     * 按 runId 读状态；不存在返回 null。
     */
    WorkflowState load(String runId);
}
