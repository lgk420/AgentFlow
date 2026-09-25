package com.agentflow.runtime.checkpoint;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import com.agentflow.engine.model.state.WorkflowState;

/**
 * 内存版 checkpoint 存储：ConcurrentHashMap，create/load/update 都走 {@link WorkflowState#snapshot()}。
 *
 * <p>T7.3 起由 {@link RedisCheckpointStore} 作为 Spring bean（事件驱动需多 worker 共享状态），
 * 本类**不再是 @Component**，仅测试/单测直接 {@code new} 使用（避免双 bean 歧义）。
 *
 * <p><b>并发语义与 Redis 版对齐（Bug 08）</b>：{@link #update} 同样是「读快照 → 应用 mutator →
 * CAS 提交」的乐观并发，用 {@link ConcurrentMap#replace(Object, Object, Object)} 做原子比对。
 * 因此本类同样会走重试路径、同样吃 {@link CheckpointStore#update} 的两条契约，可以拿它跑并发用例
 * （不开 Docker 也能回归「并发写不丢更新」）；与 Redis 版的差别只在存储介质，
 * 以及 CAS 冲突窗口远小于网络往返。
 */
public class InMemoryCheckpointStore implements CheckpointStore {

    /**
     * CAS 冲突重试上限。比 Redis 版宽：内存版没有网络往返天然错峰，几个线程纯自旋时
     * 某个线程可能连续输很多次（并发用例里真实触发过重试超限），配合冲突时的 {@link Thread#onSpinWait()} 使用。
     */
    private static final int MAX_CAS_RETRY = 100;

    private final ConcurrentMap<String, WorkflowState> store = new ConcurrentHashMap<>();

    @Override
    public void create(WorkflowState state) {
        state.setVersion(0);
        if (store.putIfAbsent(state.getRunId(), state.snapshot()) != null) {
            throw new IllegalStateException("run 已存在，拒绝覆盖: " + state.getRunId());
        }
    }

    @Override
    public <T> T update(String runId, Function<WorkflowState, T> mutator) {
        for (int attempt = 0; attempt < MAX_CAS_RETRY; attempt++) {
            WorkflowState current = store.get(runId);
            if (current == null) {
                throw new IllegalStateException("checkpoint 不存在: " + runId);
            }
            // 在快照副本上改，改坏也不影响存储；提交时用「引用没被换过」做 CAS
            WorkflowState fresh = current.snapshot();
            T result = mutator.apply(fresh);
            fresh.setVersion(current.getVersion() + 1);
            if (store.replace(runId, current, fresh.snapshot())) {
                return result;
            }
            Thread.onSpinWait(); // 冲突：让出 CPU，降低活锁概率
        }
        throw new IllegalStateException("checkpoint CAS 重试超限: " + runId);
    }

    @Override
    public WorkflowState load(String runId) {
        WorkflowState state = store.get(runId);
        return state == null ? null : state.snapshot();
    }
}
