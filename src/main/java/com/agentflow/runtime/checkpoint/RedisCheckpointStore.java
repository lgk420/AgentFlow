package com.agentflow.runtime.checkpoint;

import com.agentflow.core.state.WorkflowState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 版 checkpoint 存储（T7.3，P3 并入 P7）——事件驱动的多 worker 共享状态。
 *
 * <p>key = {@code run:{runId}}，值 = WorkflowState 的 JSON 快照。JSON 序列化天然做深拷贝
 * （序列化/反序列化都是新对象，无引用污染，等价于 InMemory 的 snapshot 语义）。
 *
 * <p>并发写（多个 node-worker 并行执行同一 run 的节点）由 T7.4 的幂等/重算保证正确性，
 * 本类只做 save/load 基础能力。
 */
@Component
public class RedisCheckpointStore implements CheckpointStore {

    private static final String KEY_PREFIX = "run:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisCheckpointStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public void save(WorkflowState state) {
        try {
            redis.opsForValue().set(KEY_PREFIX + state.getRunId(), mapper.writeValueAsString(state));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("checkpoint 序列化失败: " + state.getRunId(), e);
        }
    }

    @Override
    public WorkflowState load(String runId) {
        String json = redis.opsForValue().get(KEY_PREFIX + runId);
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, WorkflowState.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("checkpoint 反序列化失败: " + runId, e);
        }
    }
}
