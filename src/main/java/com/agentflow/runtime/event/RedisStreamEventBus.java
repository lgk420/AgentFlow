package com.agentflow.runtime.event;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis Streams 实现的事件总线（T7.1，QA 70）——XADD 发布 / XREADGROUP 消费 / XACK 确认。
 *
 * <p>payload 整体序列化成 JSON 存进消息的单个 {@code payload} 字段（StringRedisTemplate 下 HK/HV 都是 String，
 * 不逐字段平铺，嵌套结构天然支持）；消费组不存在时幂等创建（BUSYGROUP 忽略）。
 *
 * <p>at-least-once：只有调用方处理成功后 ack，未 ack 的消息留在 Pending（PEL）可重投（T7.4）。
 */
@Component
public class RedisStreamEventBus implements EventBus {

    private static final String PAYLOAD_FIELD = "payload";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisStreamEventBus(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public String publish(String topic, Map<String, Object> payload) {
        String json = toJson(payload);
        RecordId id = streamOps().add(
                StreamRecords.<String, String, String>mapBacked(Map.of(PAYLOAD_FIELD, json)).withStreamKey(topic));
        return id.getValue();
    }

    @Override
    public List<EventMessage> read(String topic, String group, String consumer, int batchSize, long blockMs) {
        ensureGroup(topic, group);
        StreamReadOptions readOptions = StreamReadOptions.empty()
                .count(batchSize)
                .block(Duration.ofMillis(blockMs));
        List<MapRecord<String, String, String>> records = streamOps().read(
                Consumer.from(group, consumer),
                readOptions,
                StreamOffset.create(topic, ReadOffset.lastConsumed()));
        return records.stream()
                .map(r -> new EventMessage(r.getId().getValue(), fromJson(r.getValue().get(PAYLOAD_FIELD))))
                .toList();
    }

    @Override
    public void ack(String topic, String group, String messageId) {
        streamOps().acknowledge(topic, group, messageId);
    }

    private StreamOperations<String, String, String> streamOps() {
        return redis.opsForStream();
    }

    /**
     * 消费组不存在则创建；已存在（BUSYGROUP）忽略——幂等。
     *
     * <p>从流起始（offset 0）建组：组创建前已发布的消息也会被投递（不丢事件，at-least-once），
     * 重放导致的重复由 T7.4 幂等键兜底。若用 latest($)，组创建前的消息会被永久跳过。
     */
    private void ensureGroup(String topic, String group) {
        try {
            streamOps().createGroup(topic, ReadOffset.from("0"), group);
        } catch (RuntimeException e) {
            if (busyGroup(e)) {
                return;
            }
            throw e;
        }
    }

    private static boolean busyGroup(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (String.valueOf(t.getMessage()).contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件序列化失败: " + payload, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件反序列化失败: " + json, e);
        }
    }
}
