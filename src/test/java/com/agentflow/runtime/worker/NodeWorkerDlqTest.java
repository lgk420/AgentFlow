package com.agentflow.runtime.worker;

import java.util.List;
import java.util.Map;

import com.agentflow.core.exec.GraphRuntime;
import com.agentflow.core.routing.ConditionEvaluator;
import com.agentflow.core.store.WorkflowStore;
import com.agentflow.runtime.checkpoint.RedisCheckpointStore;
import com.agentflow.runtime.queue.EventBus;
import com.agentflow.runtime.queue.EventCodec;
import com.agentflow.runtime.queue.EventMessage;
import com.agentflow.runtime.queue.Events;
import com.agentflow.runtime.queue.RedisStreamEventBus;
import com.agentflow.runtime.queue.Streams;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7.5 死信测试——进程级处理失败重投超限 → 进死信（agentflow:dlq）+ 停止重投（ACK）。
 *
 * <p>用"run 无 checkpoint"的 NodeReady 制造 process 必抛（"checkpoint 不存在"），
 * 重投 maxRetries+1 次 → 死信有事件（含错误栈）、原消息从 PEL 移除。
 */
@Testcontainers
class NodeWorkerDlqTest {

    private static final int MAX_RETRIES = 3;

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private StringRedisTemplate template;
    private EventBus eventBus;
    private NodeWorker worker;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        eventBus = new RedisStreamEventBus(template, mapper);
        RedisCheckpointStore checkpointStore = new RedisCheckpointStore(template, mapper);
        WorkflowStore workflowStore = new WorkflowStore(template, new com.agentflow.core.dsl.GraphParser(mapper), mapper);
        GraphRuntime graphRuntime = new GraphRuntime(new ConditionEvaluator(), null);
        worker = new NodeWorker(eventBus, new EventCodec(mapper), checkpointStore, workflowStore, graphRuntime,
                template, List.of(), MAX_RETRIES);
    }

    @AfterEach
    void tearDown() {
        if (template != null && template.getConnectionFactory() instanceof LettuceConnectionFactory lcf) {
            lcf.destroy();
        }
    }

    private List<String> nodePendingIds() {
        return template.opsForStream()
                .read(Consumer.from(Streams.NODE_WORKER, "c1"), StreamOffset.create(Streams.NODE, ReadOffset.from("0")))
                .stream()
                .map(r -> r.getId().getValue())
                .toList();
    }

    @Test
    void processFailure_exceedingMaxRetries_goesToDlqAndAcked() {
        // 发布一个 run 无 checkpoint 的 NodeReady → process 必抛"checkpoint 不存在"
        String msgId = eventBus.publish(Streams.NODE,
                new EventCodec(new ObjectMapper()).toPayload(Events.NodeReady.of("no-such-run", "a")));

        // 投递进 PEL
        List<EventMessage> delivered = eventBus.read(Streams.NODE, Streams.NODE_WORKER, "c1", 10, 200);
        assertThat(delivered).hasSize(1);
        EventMessage event = delivered.get(0);
        assertThat(event.id()).isEqualTo(msgId);

        // 模拟重投：process 抛 + onProcessFailure 计数，共 maxRetries+1 次（第 4 次超限）
        for (int i = 0; i <= MAX_RETRIES; i++) {
            try {
                worker.process(event);
            } catch (Exception e) {
                worker.onProcessFailure(event, e);
            }
        }

        // 死信有事件（含错误栈）
        List<EventMessage> dlq = eventBus.read(Streams.DLQ, "dlq-admin", "c1", 10, 200);
        assertThat(dlq).hasSize(1);
        assertThat(dlq.get(0).payload()).containsEntry("type", "DLQ")
                .containsEntry("runId", "no-such-run")
                .containsEntry("nodeId", "a")
                .containsEntry("messageId", msgId)
                .containsKey("error")
                .containsKey("stack");

        // 原消息已 ACK（从 PEL 移除）→ 停止重投
        assertThat(nodePendingIds()).isEmpty();
    }
}
