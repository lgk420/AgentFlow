package com.agentflow.runtime.checkpoint;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentflow.core.state.NodeOutput;
import com.agentflow.core.state.NodeStatus;
import com.agentflow.core.state.RunStatus;
import com.agentflow.core.state.WorkflowState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7.3 前置 · RedisCheckpointStore 测试（Testcontainers redis）。
 *
 * <p>验证：save→load 往返（含 inputs/nodeOutputs/status/error/Instant 时间戳，JSON 序列化）；
 * load 不存在返回 null。
 */
@Testcontainers
class RedisCheckpointStoreTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private RedisCheckpointStore store;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        store = new RedisCheckpointStore(template,
                new ObjectMapper().findAndRegisterModules()); // JavaTimeModule 处理 Instant
    }

    @AfterEach
    void tearDown() {
        store = null;
    }

    @Test
    void saveLoad_roundTrip_preservesAllFields() {
        WorkflowState state = new WorkflowState();
        state.setRunId("run-1");
        state.setWorkflowId("wf-1");
        state.setStatus(RunStatus.SUCCEEDED);
        state.setInputs(Map.of("userMessage", "练背"));
        state.getNodeOutputs().put("parse",
                new NodeOutput("parse", Map.of("date", "20260808"), null, NodeStatus.SUCCEEDED));
        state.setCreatedAt(Instant.parse("2026-08-31T10:00:00Z"));
        state.setUpdatedAt(Instant.parse("2026-08-31T10:01:00Z"));
        state.setError("运行失败示例");

        store.create(state);

        WorkflowState loaded = store.load("run-1");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getRunId()).isEqualTo("run-1");
        assertThat(loaded.getWorkflowId()).isEqualTo("wf-1");
        assertThat(loaded.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(loaded.getInputs()).isEqualTo(Map.of("userMessage", "练背"));
        assertThat(loaded.getError()).isEqualTo("运行失败示例");
        assertThat(loaded.getNodeOutputs().get("parse").getOutput()).isEqualTo(Map.of("date", "20260808"));
        assertThat(loaded.getNodeOutputs().get("parse").getStatus()).isEqualTo(NodeStatus.SUCCEEDED);
        assertThat(loaded.getCreatedAt()).isEqualTo(Instant.parse("2026-08-31T10:00:00Z"));
    }

    @Test
    void load_missing_returnsNull() {
        assertThat(store.load("no-such-run")).isNull();
    }

    @Test
    void update_incrementsVersion_andRoundTripsIt() {
        WorkflowState state = new WorkflowState();
        state.setRunId("run-v");
        store.create(state);
        assertThat(store.load("run-v").getVersion()).isZero();

        store.update("run-v", fresh -> {
            fresh.getNodeOutputs().put("a", new NodeOutput("a", "a-out", null, NodeStatus.SUCCEEDED));
            return null;
        });

        WorkflowState after = store.load("run-v");
        assertThat(after.getVersion()).isEqualTo(1);
        assertThat(after.getNodeOutputs()).containsKey("a");
    }

    /** 强制两个写者都「已 load、未提交」后再提交：CAS 必须让后提交者重试，而不是静默覆盖。 */
    @Test
    void update_forcedConflict_keepsBothFacts() throws Exception {
        WorkflowState s = new WorkflowState();
        s.setRunId("run-c");
        store.create(s);

        CountDownLatch bothLoaded = new CountDownLatch(2);
        AtomicInteger mutatorCalls = new AtomicInteger();

        Runnable writerA = () -> store.update("run-c", fresh -> {
            awaitFirstRound(mutatorCalls, bothLoaded);
            fresh.getNodeOutputs().put("a", new NodeOutput("a", "ao", null, NodeStatus.SUCCEEDED));
            return null;
        });
        Runnable writerB = () -> store.update("run-c", fresh -> {
            awaitFirstRound(mutatorCalls, bothLoaded);
            fresh.getNodeOutputs().put("b", new NodeOutput("b", "bo", null, NodeStatus.SUCCEEDED));
            return null;
        });

        Thread t1 = new Thread(writerA);
        Thread t2 = new Thread(writerB);
        t1.start();
        t2.start();
        t1.join(10_000);
        t2.join(10_000);

        WorkflowState after = store.load("run-c");
        assertThat(after.getNodeOutputs()).containsKeys("a", "b");
        assertThat(after.getVersion()).isEqualTo(2); // 两次提交串行化，没有谁被静默吞掉
    }

    /** 前两次调用（两个写者各自的首次）在栅栏上等齐；重试调用不再等待，避免死等。 */
    private static void awaitFirstRound(AtomicInteger mutatorCalls, CountDownLatch bothLoaded) {
        if (mutatorCalls.getAndIncrement() < 2) {
            bothLoaded.countDown();
            try {
                bothLoaded.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
