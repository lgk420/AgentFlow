package com.agentflow.runtime.checkpoint;

import java.util.List;
import java.util.function.Function;

import com.agentflow.core.state.WorkflowState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 版 checkpoint 存储（T7.3，P3 并入 P7）——事件驱动的多 worker 共享状态。
 *
 * <p>key = {@code run:{runId}}，值 = WorkflowState 的 JSON 快照（{@code version} 字段就在文档里，
 * 因此单 key WATCH 足够，不需要把状态拆成多个 key）。JSON 序列化天然做深拷贝
 * （序列化/反序列化都是新对象，无引用污染，等价于 InMemory 的 snapshot 语义）。
 *
 * <p><b>并发写（Bug 08）</b>：多个 node-worker 并行执行同一 run 的节点时，靠 {@link #update} 的
 * version CAS 串行化提交。早期实现是整份覆盖写、且误以为 T7.4 的幂等键能覆盖此竞态——幂等键防「重复」，
 * 防不住「丢失」，两码事。详见 {@link CheckpointStore} 的接口契约。
 */
@Component
public class RedisCheckpointStore implements CheckpointStore {

    private static final String KEY_PREFIX = "run:";

    /**
     * CAS 冲突重试上限。冲突说明有别的 worker 正在推进、不是错误；重试几轮总能提交上。
     * 持续打满上限说明争用异常，抛出交给上层（事件重投 / 死信）而不是无限自旋。
     */
    private static final int MAX_CAS_RETRY = 20;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisCheckpointStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public void create(WorkflowState state) {
        state.setVersion(0);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(KEY_PREFIX + state.getRunId(), serialize(state)))) {
            throw new IllegalStateException("run 已存在，拒绝覆盖: " + state.getRunId());
        }
    }

    @Override
    public <T> T update(String runId, Function<WorkflowState, T> mutator) {
        String key = KEY_PREFIX + runId;
        for (int attempt = 0; attempt < MAX_CAS_RETRY; attempt++) {
            WorkflowState fresh = load(runId);
            if (fresh == null) {
                throw new IllegalStateException("checkpoint 不存在: " + runId);
            }
            // mutator 在「本次读到的快照」上应用事实并推导派生结果（见接口契约第 2 条）
            T result = mutator.apply(fresh);
            if (commit(key, fresh)) {
                return result;
            }
        }
        throw new IllegalStateException("checkpoint CAS 重试超限: " + runId);
    }

    /**
     * CAS 提交：WATCH → 在 watch 内重读比对 version → MULTI/SET → EXEC。
     *
     * <p>用 {@link SessionCallback} 而非裸调用：WATCH/MULTI/EXEC 必须落在同一条连接上，
     * 否则 WATCH 保护的不是后面那个写。
     *
     * <p>watch 内<b>重读</b>那一步不能省：{@code state} 是 watch 之前 load 的，
     * load 与 watch 之间别的 worker 可能已经提交了。WATCH 只能保证「watch 之后 key 没被改过」，
     * 保证不了「load 之后没被改过」——所以要用 version 再确认一次。
     *
     * @return 提交成功返回 true；版本已被别人推进则返回 false（调用方重试）
     */
    private boolean commit(String key, WorkflowState state) {
        int seen = state.getVersion();
        state.setVersion(seen + 1);
        String json = serialize(state);
        // 只能用匿名类不能用 lambda：SessionCallback#execute 自身带 <K,V> 泛型方法，
        // 而 lambda 只能实现抽象方法非泛型的函数式接口
        Boolean committed = redis.execute(new SessionCallback<Boolean>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Boolean execute(RedisOperations<K, V> session) {
                K rawKey = (K) key;
                session.watch(rawKey);
                WorkflowState current = deserialize((String) session.opsForValue().get(rawKey));
                if (current == null || current.getVersion() != seen) {
                    session.unwatch();
                    return false;
                }
                session.multi();
                session.opsForValue().set(rawKey, (V) json);
                // 事务被 WATCH 打断 ⇒ CAS 失败，调用方重试。
                // ⚠️ 判断「被打断」不能只判 null：Spring Data Redis 文档说 exec 返回 null，
                // 但 Lettuce 驱动下返回的是**空列表**。只判 null 会把中止当成成功，
                // 于是这次写被静默丢弃——丢失更新照样发生（Bug 08 修复过程中真实踩到过一次）。
                List<Object> execResult = session.exec();
                return execResult != null && !execResult.isEmpty();
            }
        });
        return Boolean.TRUE.equals(committed);
    }

    @Override
    public WorkflowState load(String runId) {
        return deserialize(redis.opsForValue().get(KEY_PREFIX + runId));
    }

    private String serialize(WorkflowState state) {
        try {
            return mapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("checkpoint 序列化失败: " + state.getRunId(), e);
        }
    }

    private WorkflowState deserialize(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, WorkflowState.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("checkpoint 反序列化失败", e);
        }
    }
}
