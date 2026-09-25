package com.agentflow.runtime.event;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
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
 * T7.1 RedisStreamEventBus 测试（Testcontainers redis，复用 RedisContainerBaselineTest 模式）。
 *
 * <p>验证：发布→消费→ACK 闭环；未 ACK 的消息留在 Pending（at-least-once 底座）；payload JSON 往返；
 * 同组两个消费者负载均衡（每条只投给一个消费者）。
 *
 * <p>每个用例用唯一 topic（方法名）隔离，避免跨用例污染。
 */
@Testcontainers
class RedisStreamEventBusTest {

    private static final String GROUP = "test-group";

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private String topic;
    private StringRedisTemplate template;
    private RedisStreamEventBus bus;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        topic = "test:node:" + testInfo.getTestMethod().get().getName();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        bus = new RedisStreamEventBus(template, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (template != null && template.getConnectionFactory() instanceof LettuceConnectionFactory lcf) {
            lcf.destroy();
        }
    }

    /** 底层直查 Pending（绕过 EventBus 抽象，用 offset 0 重读消费组未确认消息）。 */
    private List<String> pendingIds() {
        return template.opsForStream()
                .read(Consumer.from(GROUP, "worker-1"), StreamOffset.create(topic, ReadOffset.from("0")))
                .stream()
                .map(r -> r.getId().getValue())
                .toList();
    }

    @Test
    void publishReadAck_roundTrip() {
        String id = bus.publish(topic, Map.of("runId", "run-1", "nodeId", "kb"));

        List<EventMessage> events = bus.read(topic, GROUP, "worker-1", 10, 200);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).id()).isEqualTo(id);
        assertThat(events.get(0).payload()).isEqualTo(Map.of("runId", "run-1", "nodeId", "kb"));

        bus.ack(topic, GROUP, id);
        assertThat(pendingIds()).isEmpty(); // ACK 后 Pending 清空
    }

    @Test
    void unackedMessage_staysInPending() {
        String id = bus.publish(topic, Map.of("runId", "run-2", "nodeId", "store"));

        assertThat(bus.read(topic, GROUP, "worker-1", 10, 200)).hasSize(1); // 读但不 ACK
        assertThat(pendingIds()).containsExactly(id); // 留在 Pending → at-least-once 底座

        bus.ack(topic, GROUP, id);
        assertThat(pendingIds()).isEmpty();
    }

    @Test
    void payloadJson_roundTrip_withNestedValues() {
        bus.publish(topic, Map.of("runId", "run-3", "nested", Map.of("a", 1, "b", List.of("x", "y"))));

        List<EventMessage> events = bus.read(topic, GROUP, "worker-1", 10, 200);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).payload())
                .isEqualTo(Map.of("runId", "run-3", "nested", Map.of("a", 1, "b", List.of("x", "y"))));
    }

    @Test
    void twoConsumers_sameGroup_loadBalance() {
        bus.publish(topic, Map.of("n", 1));
        bus.publish(topic, Map.of("n", 2));

        // 批次=1：worker-1 拿一条、worker-2 拿另一条（组内负载均衡，每条只投给一个消费者）
        List<EventMessage> fromWorker1 = bus.read(topic, GROUP, "worker-1", 1, 200);
        List<EventMessage> fromWorker2 = bus.read(topic, GROUP, "worker-2", 1, 200);

        assertThat(fromWorker1).hasSize(1);
        assertThat(fromWorker2).hasSize(1);
        assertThat(fromWorker1.get(0).id()).isNotEqualTo(fromWorker2.get(0).id());
    }
}
