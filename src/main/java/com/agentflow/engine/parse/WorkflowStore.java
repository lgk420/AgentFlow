package com.agentflow.engine.parse;

import java.util.List;
import java.util.Set;

import com.agentflow.engine.parse.GraphParser;
import com.agentflow.engine.model.definition.WorkflowDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 工作流定义存储（Redis）。
 *
 * <p>数据模型（见 DSL使用说明 2.1 / QA 32）：
 * <ul>
 *   <li>{@code wf:{id}:{version}} —— Hash，字段 {@code json} = 完整工作流 JSON 字符串；</li>
 *   <li>{@code wf:versions:{id}} —— Sorted Set，成员=版本号、score=版本号，用于列版本与取最新。</li>
 * </ul>
 *
 * <p>同一 id 多版本并存互不覆盖；同 id+version 重复 save 视为 upsert（覆盖，幂等）。
 * 写入用 ObjectMapper 序列化，读回用 {@link GraphParser} 反序列化（保证节点 id 从键填充）。
 */
@Component
public class WorkflowStore {

    private static final String KEY_PREFIX = "wf:";
    private static final String VERSIONS_KEY_PREFIX = "wf:versions:";
    private static final String JSON_FIELD = "json";

    private final StringRedisTemplate redis;
    private final GraphParser parser;
    private final ObjectMapper mapper;

    public WorkflowStore(StringRedisTemplate redis, GraphParser parser, ObjectMapper mapper) {
        this.redis = redis;
        this.parser = parser;
        this.mapper = mapper;
    }

    /**
     * 保存工作流：写入 {@code wf:{id}:{version}} Hash，并把版本登记进 Sorted Set 索引。
     */
    public void save(WorkflowDefinition wf) {
        String id = wf.getId();
        int version = wf.getVersion();
        try {
            String json = mapper.writeValueAsString(wf);
            redis.opsForHash().put(KEY_PREFIX + id + ":" + version, JSON_FIELD, json);
            redis.opsForZSet().add(VERSIONS_KEY_PREFIX + id, String.valueOf(version), version);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工作流序列化失败：" + id + ":" + version, e);
        }
    }

    /**
     * 按 id 查最新版本；不存在返回 null。
     */
    public WorkflowDefinition findById(String id) {
        Set<String> versions = redis.opsForZSet().reverseRange(VERSIONS_KEY_PREFIX + id, 0, 0);
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        int version = Integer.parseInt(versions.iterator().next());
        return findByIdAndVersion(id, version);
    }

    /**
     * 按 id + 精确版本查；不存在返回 null。
     */
    public WorkflowDefinition findByIdAndVersion(String id, int version) {
        Object json = redis.opsForHash().get(KEY_PREFIX + id + ":" + version, JSON_FIELD);
        if (json == null) {
            return null;
        }
        return parser.parse(json.toString());
    }

    /**
     * 列出某 id 的全部版本号（升序）；无版本返回空列表。
     */
    public List<Integer> findVersions(String id) {
        Set<String> versions = redis.opsForZSet().range(VERSIONS_KEY_PREFIX + id, 0, -1);
        if (versions == null) {
            return List.of();
        }
        return versions.stream().map(Integer::parseInt).toList();
    }
}
