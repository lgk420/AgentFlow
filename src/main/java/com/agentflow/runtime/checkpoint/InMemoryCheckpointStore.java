package com.agentflow.runtime.checkpoint;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.agentflow.core.state.WorkflowState;
import org.springframework.stereotype.Component;

/**
 * P2 内存版 checkpoint 存储：ConcurrentHashMap，save/load 都走 {@link WorkflowState#snapshot()}。
 *
 * <p>P3 由 Redis 实现替换（同接口），本类保留作测试/降级用途。
 */
@Component
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
