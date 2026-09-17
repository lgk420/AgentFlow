package com.agentflow.runtime.worker;

import java.util.List;
import java.util.Map;

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
 * T7.4 幂等测试——重复投递同一 NodeReady 时节点只执行一次（QA 74/80）。
 *
 * <p>验证：① checkpoint 事实兜底（执行过 → 跳过）；② SETNX 幂等键（其他 worker 已抢占/重复投递
 * 但 checkpoint 尚无输出 → 键已存在 → 跳过）。用计数执行器断言调用次数。
 */
@Testcontainers
class NodeWorkerIdempotencyTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private StringRedisTemplate template;
    private RedisCheckpointStore checkpointStore;
    private WorkflowStore workflowStore;
    private GraphRuntime graphRuntime;
    private EventBus eventBus;
    private EventCodec codec;

    /** TOOL 节点计数执行器（记录被调了几次）。 */
    static class CountingToolExecutor implements NodeExecutor {

        int calls = 0;

        @Override
        public NodeType type() {
            return NodeType.TOOL;
        }

        @Override
        public Object execute(NodeDefinition node, WorkflowState state) {
            calls++;
            return "out";
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

    private NodeWorker newWorker(CountingToolExecutor counting) {
        return new NodeWorker(eventBus, codec, checkpointStore, workflowStore, graphRuntime, template,
                List.of(counting), 3);
    }

    private void saveWorkflow() {
        WorkflowDefinition wf = new GraphParser(new ObjectMapper()).parse("""
                { "id":"idem-wf","name":"idem",
                  "nodes":{ "a":{"type":"TOOL","tool":"x"}, "end":{"type":"END"} },
                  "edges":[ {"from":"a","to":"end","type":"STATIC"}] }""");
        workflowStore.save(wf);
    }

    private void saveRun(String runId) {
        WorkflowState state = new WorkflowState();
        state.setRunId(runId);
        state.setWorkflowId("idem-wf");
        state.setInputs(Map.of());
        state.setStatus(RunStatus.RUNNING);
        checkpointStore.create(state);
    }

    private static EventMessage nodeReady(String runId, String nodeId) {
        return new EventMessage("msg-" + runId + "-" + nodeId,
                Map.of(Events.TYPE_FIELD, Events.NodeReady.TYPE, "runId", runId, "nodeId", nodeId));
    }

    @Test
    void duplicateDelivery_afterExecution_skipsViaCheckpointFact() {
        saveWorkflow();
        saveRun("run-1");
        CountingToolExecutor counting = new CountingToolExecutor();
        NodeWorker worker = newWorker(counting);

        EventMessage event = nodeReady("run-1", "a");
        worker.process(event);
        worker.process(event); // 重复投递：checkpoint 已有 a 输出 → 跳过

        assertThat(counting.calls).isEqualTo(1); // 只执行一次
    }

    @Test
    void duplicateDelivery_idempotencyKeyPreventsExecution() {
        saveWorkflow();
        saveRun("run-2");
        CountingToolExecutor counting = new CountingToolExecutor();
        NodeWorker worker = newWorker(counting);

        // 模拟其他 worker 已抢占幂等键（checkpoint 尚无 a 输出，只有键）
        template.opsForValue().set("run:run-2:exec:a:done", "1");

        worker.process(nodeReady("run-2", "a"));

        assertThat(counting.calls).isZero(); // SETNX 失败 → 跳过，不执行
        assertThat(checkpointStore.load("run-2").getNodeOutputs()).doesNotContainKey("a");
    }
}
