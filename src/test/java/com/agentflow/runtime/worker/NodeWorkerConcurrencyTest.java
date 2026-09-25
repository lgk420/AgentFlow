package com.agentflow.runtime.worker;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentflow.core.dsl.GraphParser;
import com.agentflow.core.exec.GraphRuntime;
import com.agentflow.core.exec.NodeExecutor;
import com.agentflow.core.model.NodeDefinition;
import com.agentflow.core.model.NodeType;
import com.agentflow.core.model.WorkflowDefinition;
import com.agentflow.core.routing.ConditionEvaluator;
import com.agentflow.core.state.RunStatus;
import com.agentflow.core.state.WorkflowState;
import com.agentflow.core.store.WorkflowStore;
import com.agentflow.runtime.stream.RunProgressBus;
import com.agentflow.runtime.checkpoint.RedisCheckpointStore;
import com.agentflow.runtime.queue.EventBus;
import com.agentflow.runtime.queue.EventCodec;
import com.agentflow.runtime.queue.EventMessage;
import com.agentflow.runtime.queue.Events;
import com.agentflow.runtime.queue.RedisStreamEventBus;
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
 * Bug 08 回归测试——多 worker 并发处理<b>同一 run 的不同节点</b>时的 checkpoint 正确性。
 *
 * <p>此前所有测试都是单 worker 串行，测不出这个竞态（与 Bug 01/02/03「桩测不出真实链路问题」同源）。
 *
 * <p>两个断言对应两个不同的缺陷，且都必须靠「并发」才暴露：
 * <ol>
 *   <li><b>丢失更新</b>——两个 worker 各自 load 到同一份快照再整份写回，后写者覆盖先写者的节点输出；</li>
 *   <li><b>漏发（lost wakeup）</b>——fan-in 节点 end 的两条入边分别由两个 worker 解析。若各自拿
 *       「自己 load 时的旧快照」算就绪集，双方都看不到对方刚产生的解析结果，判定 end 不就绪，
 *       于是谁都不发 NodeReady(end)，run 永远卡在中间。</li>
 * </ol>
 *
 * <p>执行器里放了一道栅栏，强制两个 worker 都进入「已 load、未提交」的窗口后才一起往下走，
 * 让竞态稳定复现（否则线程可能天然错开而侥幸通过）。
 */
@Testcontainers
class NodeWorkerConcurrencyTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final String WF_ID = "fan-in-wf";
    private static final String RUN_ID = "run-concurrent";

    private StringRedisTemplate template;
    private RedisCheckpointStore checkpointStore;
    private WorkflowStore workflowStore;
    private GraphRuntime graphRuntime;
    private EventBus eventBus;
    private EventCodec codec;

    /**
     * 两个 worker 共用的 TOOL 执行器：在执行点等齐，确保两边都「已 load、未提交」时才一起提交。
     */
    static class BarrierToolExecutor implements NodeExecutor {

        private final CyclicBarrier barrier;
        final AtomicInteger calls = new AtomicInteger();

        BarrierToolExecutor(CyclicBarrier barrier) {
            this.barrier = barrier;
        }

        @Override
        public NodeType type() {
            return NodeType.TOOL;
        }

        @Override
        public Object execute(NodeDefinition node, WorkflowState state) {
            calls.incrementAndGet();
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("测试栅栏等待失败", e);
            }
            return node.getId() + "-out";
        }
    }

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        checkpointStore = new RedisCheckpointStore(template, mapper);
        workflowStore = new WorkflowStore(template, new GraphParser(mapper), mapper);
        eventBus = new RedisStreamEventBus(template, mapper);
        codec = new EventCodec(mapper);
        graphRuntime = new GraphRuntime(new ConditionEvaluator(), null);
    }

    @AfterEach
    void tearDown() {
        if (template != null && template.getConnectionFactory() instanceof LettuceConnectionFactory lcf) {
            lcf.destroy();
        }
    }

    private NodeWorker newWorker(NodeExecutor executor) {
        return new NodeWorker(eventBus, codec, checkpointStore, workflowStore, graphRuntime, template,
                new RunProgressBus(), List.of(executor), 3);
    }

    /** start → {a, b} → end 的 diamond：a、b 同时就绪，由两个 worker 分别处理。 */
    private void saveWorkflow() {
        WorkflowDefinition wf = new GraphParser(new ObjectMapper()).parse("""
                { "id":"fan-in-wf","name":"fan-in",
                  "nodes":{ "start":{"type":"START"}, "a":{"type":"TOOL","tool":"x"},
                            "b":{"type":"TOOL","tool":"y"}, "end":{"type":"END"} },
                  "edges":[ {"from":"start","to":"a","type":"STATIC"},
                            {"from":"start","to":"b","type":"STATIC"},
                            {"from":"a","to":"end","type":"STATIC"},
                            {"from":"b","to":"end","type":"STATIC"} ] }""");
        workflowStore.save(wf);
    }

    private void createRun() {
        WorkflowState state = new WorkflowState();
        state.setRunId(RUN_ID);
        state.setWorkflowId(WF_ID);
        state.setInputs(Map.of());
        state.setStatus(RunStatus.RUNNING);
        checkpointStore.create(state);
    }

    private static EventMessage nodeReady(String nodeId) {
        return new EventMessage("msg-" + RUN_ID + "-" + nodeId,
                Map.of(Events.TYPE_FIELD, Events.NodeReady.TYPE, "runId", RUN_ID, "nodeId", nodeId));
    }

    @Test
    void concurrentWorkers_onDifferentNodes_keepBothFacts_andFanInStillFires() throws Exception {
        saveWorkflow();
        createRun();

        CyclicBarrier barrier = new CyclicBarrier(2);
        BarrierToolExecutor executor = new BarrierToolExecutor(barrier);
        NodeWorker workerForA = newWorker(executor);
        NodeWorker workerForB = newWorker(executor);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> futureA = pool.submit(() -> workerForA.process(nodeReady("a")));
            Future<?> futureB = pool.submit(() -> workerForB.process(nodeReady("b")));
            futureA.get(30, TimeUnit.SECONDS);
            futureB.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        WorkflowState state = checkpointStore.load(RUN_ID);

        // ① 丢失更新：两个 worker 的节点输出都要在（旧实现整份覆盖写，后提交者会把先提交者的冲掉）
        assertThat(state.getNodeOutputs()).containsKeys("a", "b");
        assertThat(state.getNodeOutputs().get("a").getOutput()).isEqualTo("a-out");
        assertThat(state.getNodeOutputs().get("b").getOutput()).isEqualTo("b-out");
        assertThat(executor.calls).hasValue(2); // 各自只执行一次

        // ② 漏发：就绪推导基于「提交那一刻的最新快照」，所以总有一个提交者能同时看到 a、b 的入边解析结果，
        //    把 end 推成就绪。若两边都拿自己的旧快照算，end 永远收不到 NodeReady，run 会卡在 RUNNING。
        assertThat(state.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(state.getNodeOutputs()).containsKey("end");

        // ③ END 聚合的是直接前驱（a、b）的输出，两个都要在——证明聚合时看到的是合并后的事实
        @SuppressWarnings("unchecked")
        Map<String, Object> endResult = (Map<String, Object>) state.getNodeOutputs().get("end").getOutput();
        assertThat(endResult).containsEntry("a", "a-out").containsEntry("b", "b-out");
    }
}
