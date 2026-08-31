package com.agentflow.runtime.queue;

import java.util.List;
import java.util.Map;

/**
 * 事件总线抽象（T7.1，架构 8）——纯语义，不泄漏 Redis Streams 细节。
 *
 * <p>语义：<b>at-least-once 投递 + 显式确认</b>。消费端 {@link #read} 后必须处理成功才
 * {@link #ack}；未 ACK 的消息留在 Pending，后续可重投（T7.4 幂等兜底重复副作用）。
 *
 * <p>轮询式而非订阅式（QA 71）：消费端（T7.3 node-worker）本就是显式工作循环
 * （read→执行→ack→下一批），轮询式把 ACK 时机、批次、阻塞时长都握在调用方手里。
 *
 * <p><b>换 Kafka（T7.6 替换点）</b>：接口是纯语义抽象，换实现只动实现类。Kafka 对应关系：
 * <pre>{@code
 * publish(topic, payload)  → KafkaTemplate.send(topic, JSON(payload))   // 生产
 * read(topic, group, ...)  → KafkaConsumer.subscribe(topic) + poll()    // group = 消费组
 * ack(topic, group, msgId) → consumer.commitSync()                      // 手动提交位移
 * }</pre>
 * Kafka 的消费组/位移与 Redis Streams 的组/PEL 语义等价（QA 70），换过去只写一个 {@code KafkaEventBus} 实现类。
 */
public interface EventBus {

    /**
     * 发布事件到主题（追加进流），返回消息 id（供确认/排障）。
     *
     * @param topic   主题名（如 {@code agentflow:node}）
     * @param payload 事件内容（JSON 可序列化对象）
     * @return 消息 id
     */
    String publish(String topic, Map<String, Object> payload);

    /**
     * 从消费组阻塞读取一批事件（组内负载均衡：每条只投给一个消费者）。
     *
     * @param topic     主题名
     * @param group     消费组名（不存在则自动创建）
     * @param consumer  消费者名（组内标识，用于区分不同 worker）
     * @param batchSize 一批最多读多少条
     * @param blockMs   无消息时阻塞等待时长（ms）
     * @return 待处理事件；处理成功后必须 {@link #ack}
     */
    List<EventMessage> read(String topic, String group, String consumer, int batchSize, long blockMs);

    /**
     * 确认消息已处理（从 Pending 移除）；不确认则留在 Pending 后续重投。
     */
    void ack(String topic, String group, String messageId);
}
