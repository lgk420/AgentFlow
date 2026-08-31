package com.agentflow.runtime.checkpoint;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.agentflow.core.state.WorkflowState;

/**
 * 内存版 checkpoint 存储：ConcurrentHashMap，save/load 都走 {@link WorkflowState#snapshot()}。
 *
 * <p>T7.3 起由 {@link RedisCheckpointStore} 作为 Spring bean（事件驱动需多 worker 共享状态），
 * 本类**不再是 @Component**，仅测试/单测直接 {@code new} 使用（避免双 bean 歧义）。
 */
public class InMemoryCheckpointStore implements CheckpointStore {

    private final ConcurrentMap<String, WorkflowState> store = new ConcurrentHashMap<>();

    @Override
    public void save(WorkflowState state) {
        store.put(state.getRunId(), state.snapshot());
    }

    @Override
    public WorkflowState load(String runId) {
        WorkflowState state = store.get(runId);
        return state == null ? null : state.snapshot();
    }
}
