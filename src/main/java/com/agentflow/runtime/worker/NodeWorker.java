package com.agentflow.runtime.worker;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentflow.core.exec.GraphRuntime;
import com.agentflow.core.exec.NodeExecutor;
import com.agentflow.core.exec.RetryPolicy;
import com.agentflow.core.model.EdgeDefinition;
import com.agentflow.core.model.NodeDefinition;
import com.agentflow.core.model.NodeType;
import com.agentflow.core.model.WorkflowDefinition;
import com.agentflow.core.state.NodeOutput;
import com.agentflow.core.state.NodeStatus;
import com.agentflow.core.state.RunStatus;
import com.agentflow.core.state.WorkflowState;
import com.agentflow.core.store.WorkflowStore;
import com.agentflow.runtime.checkpoint.CheckpointStore;
import com.agentflow.runtime.queue.EventBus;
import com.agentflow.runtime.queue.EventCodec;
import com.agentflow.runtime.queue.EventMessage;
import com.agentflow.runtime.queue.Events;
import com.agentflow.runtime.queue.Streams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 节点执行器（T7.3）——消费 {@link Streams#NODE} 的 NodeReady：
 * 执行节点 → 更新 checkpoint → {@link GraphRuntime} 算就绪集 → 发下游 NodeReady / 到 END 完成运行 → ACK。
 *
 * <p>失败不 ACK（留在 PEL 重投，at-least-once）；节点已执行/已死则跳过（幂等，checkpoint 事实兜底）。
 * 单 worker 下事件顺序处理，无并发 checkpoint 写；多 worker 并发写由 T7.4 处理。
 */
@Component
@ConditionalOnProperty(name = "core.event-driven.enabled", havingValue = "true", matchIfMissing = true)
public class NodeWorker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NodeWorker.class);

    /**
     * 幂等键 TTL（T7.5）：键到期后崩溃的 claim 可被重投重新执行（修复 T7.4 崩溃卡住）。
     */
    private static final Duration IDEM_KEY_TTL = Duration.ofSeconds(60);

    private final EventBus eventBus;
    private final EventCodec codec;
    private final CheckpointStore checkpointStore;
    private final WorkflowStore workflowStore;
    private final GraphRuntime graphRuntime;
    private final StringRedisTemplate redis;
    private final int maxRetries;
    private final Map<NodeType, NodeExecutor> executors;

    public NodeWorker(EventBus eventBus, EventCodec codec, CheckpointStore checkpointStore,
                      WorkflowStore workflowStore, GraphRuntime graphRuntime, StringRedisTemplate redis,
                      List<NodeExecutor> executors,
                      @Value("${core.event-driven.max-retries:3}") int maxRetries) {
        this.eventBus = eventBus;
        this.codec = codec;
        this.checkpointStore = checkpointStore;
        this.workflowStore = workflowStore;
        this.graphRuntime = graphRuntime;
        this.redis = redis;
        this.maxRetries = maxRetries;
        this.executors = new EnumMap<>(NodeType.class);
        executors.forEach(ex -> this.executors.put(ex.type(), ex));
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread worker = new Thread(this::loop, "node-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void loop() {
        while (true) {
            List<EventMessage> events;
            try {
                events = eventBus.read(Streams.NODE, Streams.NODE_WORKER, consumerName(), 10, 2000);
            } catch (Exception e) {
                log.warn("node 事件读取失败，重试", e);
                continue;
            }
            for (EventMessage event : events) {
                try {
                    process(event);
                    eventBus.ack(Streams.NODE, Streams.NODE_WORKER, event.id());
                } catch (Exception e) {
                    onProcessFailure(event, e); // T7.5：计数 → 超限进死信 + ACK，否则留在 PEL 重投
                }
            }
        }
    }

    void process(EventMessage event) {
        String runId = (String) event.payload().get("runId");
        String nodeId = (String) event.payload().get("nodeId");

        WorkflowState state = checkpointStore.load(runId);
        if (state == null) {
            throw new IllegalStateException("checkpoint 不存在: " + runId);
        }
        // 幂等①：已执行/已死节点直接跳过（checkpoint 事实兜底）
        if (state.getNodeOutputs().containsKey(nodeId) || state.getDeadNodes().contains(nodeId)) {
            return;
        }
        WorkflowDefinition wf = workflowStore.findById(state.getWorkflowId());
        if (wf == null) {
            throw new IllegalStateException("工作流不存在: " + state.getWorkflowId());
        }

        // 幂等②（T7.4，QA 80）：SETNX 抢占，键已存在 → 重复投递/其他 worker 已在处理 → 跳过，节点只执行一次。
        // 带 TTL（T7.5）：崩溃的 claim 到期后，重投可重新执行（修复 T7.4 崩溃卡住）
        String idemKey = "run:" + runId + ":exec:" + nodeId + ":done";
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(idemKey, "1", IDEM_KEY_TTL))) {
            return;
        }

        Object output;
        try {
            output = executeNodeWithRetry(nodeId, wf, state);
        } catch (Exception e) {
            failRun(state, nodeId, e);
            return;
        }

        state.getNodeOutputs().put(nodeId, new NodeOutput(nodeId, output, null, NodeStatus.SUCCEEDED));
        checkpointStore.save(state);

        List<String> ready = graphRuntime.resolveOutEdges(nodeId, wf, state, false);
        checkpointStore.save(state); // 边解析结果持久化

        for (String next : ready) {
            if (wf.getNodes().get(next).getType() == NodeType.END) {
                completeRun(state, wf, next);
            } else {
                eventBus.publish(Streams.NODE, codec.toPayload(Events.NodeReady.of(runId, next)));
            }
        }
    }

    /**
     * T7.5 处理失败：重试计数（INCR）→ 超限进死信 + ACK（停止重投）；否则留在 PEL 重投。
     */
    void onProcessFailure(EventMessage event, Exception e) {
        long retries = redis.opsForValue().increment("dlq:retry:" + event.id());
        if (retries > maxRetries) {
            publishDlq(event, e);
            eventBus.ack(Streams.NODE, Streams.NODE_WORKER, event.id());
            log.error("已达最大重试 {} 次，进入死信: {} error={}", maxRetries, event.id(), e.getMessage());
        } else {
            log.warn("节点处理失败，等待重投({}/{}): {} error={}", retries, maxRetries, event.id(), e.getMessage());
        }
    }

    /**
     * T7.5 推死信：原事件 + 错误栈 到 {@link Streams#DLQ}（供人工排查/告警）。
     */
    private void publishDlq(EventMessage event, Exception e) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "DLQ");
        payload.put("sourceTopic", Streams.NODE);
        payload.put("originalType", event.payload().get(Events.TYPE_FIELD));
        payload.put("runId", event.payload().get("runId"));
        payload.put("nodeId", event.payload().get("nodeId"));
        payload.put("messageId", event.id());
        payload.put("error", e.getMessage());
        payload.put("stack", stackTraceToString(e));
        eventBus.publish(Streams.DLQ, payload);
    }

    private static String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /**
     * 节点执行 + 重试（T2.6）：按 retryPolicy 重试，耗尽才抛错（由调用方判 FAILED）。
     */
    private Object executeNodeWithRetry(String nodeId, WorkflowDefinition wf, WorkflowState state) {
        NodeDefinition node = wf.getNodes().get(nodeId);
        NodeExecutor executor = executors.get(node.getType());
        if (executor == null) {
            throw new IllegalStateException("节点类型未实现：" + node.getType());
        }
        RetryPolicy policy = RetryPolicy.fromConfig(node.getConfig());
        int maxAttempts = policy.getRetries() + 1;
        int attempt = 0;
        Throwable lastError = null;
        while (attempt < maxAttempts) {
            try {
                return executor.execute(node, state);
            } catch (Exception e) {
                lastError = e;
                attempt++;
                if (attempt < maxAttempts) {
                    sleepQuietly(policy.getBackoffMs());
                }
            }
        }
        throw new IllegalStateException("节点执行失败：" + nodeId + "：" + lastError.getMessage(), lastError);
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 节点失败 → run FAILED：节点标记 FAILED + 运行级 error + 发 RunCompleted(FAILED)。
     */
    private void failRun(WorkflowState state, String nodeId, Exception cause) {
        state.getNodeOutputs().put(nodeId,
                new NodeOutput(nodeId, null, cause.getMessage(), NodeStatus.FAILED));
        state.setError("节点执行失败：" + nodeId + "：" + cause.getMessage());
        state.setStatus(RunStatus.FAILED);
        state.setUpdatedAt(Instant.now());
        checkpointStore.save(state);
        eventBus.publish(Streams.RUN,
                codec.toPayload(Events.RunCompleted.of(state.getRunId(), RunStatus.FAILED.name(), state.getError())));
    }

    /**
     * 到 END：聚合直接前驱成功输出（QA 37）+ 标记运行 SUCCEEDED + 发 RunCompleted。
     */
    private void completeRun(WorkflowState state, WorkflowDefinition wf, String endId) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (EdgeDefinition edge : wf.getEdges()) {
            if (endId.equals(edge.getTo())) {
                NodeOutput pred = state.getNodeOutputs().get(edge.getFrom());
                if (pred != null && pred.getStatus() == NodeStatus.SUCCEEDED) {
                    result.put(edge.getFrom(), pred.getOutput());
                }
            }
        }
        state.getNodeOutputs().put(endId, new NodeOutput(endId, result, null, NodeStatus.SUCCEEDED));
        state.setStatus(RunStatus.SUCCEEDED);
        state.setUpdatedAt(Instant.now());
        checkpointStore.save(state);
        eventBus.publish(Streams.RUN,
                codec.toPayload(Events.RunCompleted.of(state.getRunId(), RunStatus.SUCCEEDED.name(), null)));
    }

    private static String consumerName() {
        return "node-worker-" + System.nanoTime(); // 每实例唯一（QA 76）
    }
}
