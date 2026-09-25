package com.agentflow.runtime.event;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7.2 事件契约测试——三类事件发布到对应流、对应消费组消费、ACK 闭环，且 XINFO GROUPS 可见消费组。
 *
 * <p>验证的契约：RunStarted→{@link Streams#RUN}/run-worker、NodeReady→{@link Streams#NODE}/node-worker、
 * Trace→{@link Streams#TRACE}/trace-writer；payload 带 type 判别字段（QA 72）。
 */
@Testcontainers
class EventBusContractTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private StringRedisTemplate template;
    private RedisStreamEventBus bus;
    private EventCodec codec;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        bus = new RedisStreamEventBus(template, new ObjectMapper());
        codec = new EventCodec(new ObjectMapper());
        // 清理三个流，保证用例隔离
        template.delete(Streams.RUN);
        template.delete(Streams.NODE);
        template.delete(Streams.TRACE);
    }

    @AfterEach
    void tearDown() {
        if (template != null && template.getConnectionFactory() instanceof LettuceConnectionFactory lcf) {
            lcf.destroy();
        }
    }

    /** 底层直查 Pending（offset 0 重读），只取 id 列表供判空。 */
    private List<String> pendingIds(String topic, String group) {
        return template.opsForStream()
                .read(Consumer.from(group, "worker-1"), StreamOffset.create(topic, ReadOffset.from("0")))
                .stream()
                .map(r -> r.getId().getValue())
                .toList();
    }

    @Test
    void runStarted_flowsOnRunStream_runWorkerAcks() {
        bus.publish(Streams.RUN, codec.toPayload(Events.RunStarted.of("run-1", "wf-1", Map.of("userMessage", "练背"))));

        List<EventMessage> events = bus.read(Streams.RUN, Streams.RUN_WORKER, "worker-1", 10, 200);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).payload()).containsEntry(Events.TYPE_FIELD, Events.RunStarted.TYPE)
                .containsEntry("runId", "run-1")
                .containsEntry("workflowId", "wf-1");
        assertThat(events.get(0).payload().get("inputs")).isEqualTo(Map.of("userMessage", "练背"));

        bus.ack(Streams.RUN, Streams.RUN_WORKER, events.get(0).id());
        assertThat(pendingIds(Streams.RUN, Streams.RUN_WORKER)).isEmpty();
    }

    @Test
    void nodeReady_flowsOnNodeStream_nodeWorkerAcks() {
        bus.publish(Streams.NODE, codec.toPayload(Events.NodeReady.of("run-2", "kb")));

        List<EventMessage> events = bus.read(Streams.NODE, Streams.NODE_WORKER, "worker-1", 10, 200);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).payload()).containsEntry(Events.TYPE_FIELD, Events.NodeReady.TYPE)
                .containsEntry("runId", "run-2")
                .containsEntry("nodeId", "kb");

        bus.ack(Streams.NODE, Streams.NODE_WORKER, events.get(0).id());
        assertThat(pendingIds(Streams.NODE, Streams.NODE_WORKER)).isEmpty();
    }

    @Test
    void trace_flowsOnTraceStream_traceWriterAcks() {
        bus.publish(Streams.TRACE, codec.toPayload(Events.Trace.of("LLM",
                Map.of("model", "deepseek-v4-flash", "latencyMs", 120))));

        List<EventMessage> events = bus.read(Streams.TRACE, Streams.TRACE_WRITER, "worker-1", 10, 200);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).payload()).containsEntry(Events.TYPE_FIELD, Events.Trace.TYPE)
                .containsEntry("kind", "LLM");
        assertThat(events.get(0).payload().get("data")).isEqualTo(Map.of("model", "deepseek-v4-flash", "latencyMs", 120));

        bus.ack(Streams.TRACE, Streams.TRACE_WRITER, events.get(0).id());
        assertThat(pendingIds(Streams.TRACE, Streams.TRACE_WRITER)).isEmpty();
    }

    @Test
    void xinfoGroups_showsConsumerGroup() {
        bus.publish(Streams.NODE, codec.toPayload(Events.NodeReady.of("run-3", "store")));
        List<EventMessage> events = bus.read(Streams.NODE, Streams.NODE_WORKER, "worker-1", 10, 200);
        bus.ack(Streams.NODE, Streams.NODE_WORKER, events.get(0).id());
        bus.read(Streams.NODE, Streams.NODE_WORKER, "worker-1", 10, 50); // 空读也计入 consumer

        StreamInfo.XInfoGroups groups = template.opsForStream().groups(Streams.NODE);
        StreamInfo.XInfoGroup nodeGroup = groups.stream()
                .filter(g -> g.groupName().equals(Streams.NODE_WORKER))
                .findFirst()
                .orElseThrow(() -> new AssertionError("XINFO GROUPS 里找不到 node-worker 组"));

        assertThat(nodeGroup.consumerCount()).isGreaterThanOrEqualTo(1);
        assertThat(nodeGroup.pendingCount()).isZero();
    }
}
