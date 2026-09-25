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
import com.agentflow.runtime.stream.RunProgress;
import com.agentflow.runtime.stream.RunProgressBus;
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
 *
 * <p><b>多 worker 并发（Bug 08）</b>：同一 run 的就绪节点可能落到不同实例，checkpoint 是共享可写文档。
 * 修正点是「节点输出 + 出边解析结果在<b>一次</b> {@code update} 里原子提交，且就绪性基于那份最新快照推导」。
 * 注意 T7.4 的幂等键防的是<b>重复</b>（同一节点执行两次），防不住<b>丢失</b>（不同节点互相覆盖）——
 * 早期 javadoc 声称「多 worker 并发写由 T7.4 处理」是错的。
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
    private final RunProgressBus progressBus;
    private final int maxRetries;
    private final Map<NodeType, NodeExecutor> executors;

    public NodeWorker(EventBus eventBus, EventCodec codec, CheckpointStore checkpointStore,
                      WorkflowStore workflowStore, GraphRuntime graphRuntime, StringRedisTemplate redis,
                      RunProgressBus progressBus, List<NodeExecutor> executors,
                      @Value("${core.event-driven.max-retries:3}") int maxRetries) {
        this.eventBus = eventBus;
        this.codec = codec;
        this.checkpointStore = checkpointStore;
        this.workflowStore = workflowStore;
        this.graphRuntime = graphRuntime;
        this.redis = redis;
        this.progressBus = progressBus;
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
        // 幂等①：已执行/已死节点直接跳过（checkpoint 事实兜底）。
        // 这是「便宜短路」：真正的守卫是 update 提交时基于最新快照的判断，见下方注释
        // （Bug 08：这里的读与后面的执行之间，别的 worker 完全可能已经推进）
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
            failRun(runId, nodeId, e);
            return;
        }

        // LLM_DYNAMIC 路由先算一次：它是整条推导链里唯一会调 LLM 的一步，放进 CAS 重试循环
        // 会「冲突一次重调一次 LLM」。它只依赖本节点输出 + 出边定义，与并发无关，所以提前算安全（Bug 08）
        String dynamicTarget;
        try {
            dynamicTarget = graphRuntime.routeDynamic(nodeId, wf, output);
        } catch (Exception e) {
            failRun(runId, nodeId, e); // 路由失败按节点失败收尾，否则事件被 ACK 后 run 会永远停在 RUNNING
            return;
        }

        // Bug 08 核心：节点输出 + 出边解析结果在「一次」update 里原子提交，且 ready 基于 mutator 收到的
        // 那份最新快照推导。之前是两次整份覆盖 save、且 ready 用旧快照算好再搬过去——fan-in 时两个 worker
        // 各自在旧快照里看不到对方的入边解析结果，于是谁都不发下游 NodeReady，run 卡死
        List<String> ready = checkpointStore.update(runId, fresh -> {
            fresh.getNodeOutputs().put(nodeId, new NodeOutput(nodeId, output, null, NodeStatus.SUCCEEDED));
            return graphRuntime.resolveOutEdges(nodeId, wf, fresh, false, dynamicTarget);
        });

        // T10.4：节点已原子提交，推一条进度给 SSE 订阅者。放在提交之后——推的是既有事实，
        // 不是「即将执行」；推失败也不影响执行（RunProgressBus 内部吞掉订阅者异常）。
        progressBus.publish(RunProgress.nodeCompleted(runId, nodeId, output));

        for (String next : ready) {
            if (wf.getNodes().get(next).getType() == NodeType.END) {
                completeRun(runId, wf, next);
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
     *
     * <p>终态守卫（Bug 08）：<b>FAILED 优先</b>——SUCCEEDED 只在当前为 RUNNING 时写，FAILED 可覆盖 SUCCEEDED。
     * 否则 diamond 里「X 先到 END 判成功、同批的 Y 随后失败被挡掉」就会假成功。
     * 只有真的发生终态跃迁才发 RunCompleted，顺带消掉重复事件。
     */
    private void failRun(String runId, String nodeId, Exception cause) {
        String message = "节点执行失败：" + nodeId + "：" + cause.getMessage();
        boolean transitioned = checkpointStore.update(runId, fresh -> {
            fresh.getNodeOutputs().put(nodeId,
                    new NodeOutput(nodeId, null, cause.getMessage(), NodeStatus.FAILED));
            fresh.setError(message);
            fresh.setUpdatedAt(Instant.now());
            if (fresh.getStatus() == RunStatus.FAILED) {
                return false; // 已经是 FAILED，不重复发事件
            }
            fresh.setStatus(RunStatus.FAILED);
            return true;
        });
        if (transitioned) {
            eventBus.publish(Streams.RUN,
                    codec.toPayload(Events.RunCompleted.of(runId, RunStatus.FAILED.name(), message)));
            progressBus.publish(RunProgress.runCompleted(runId, RunStatus.FAILED.name(), message));
        }
    }

    /**
     * 到 END：聚合直接前驱成功输出（QA 37）+ 标记运行 SUCCEEDED + 发 RunCompleted。
     *
     * <p>终态守卫（Bug 08）：SUCCEEDED 只在当前为 RUNNING 时写，run 已被判 FAILED 就不许翻案。
     * diamond 的两个分支先后到 END 时，第二个不会再发一条重复的 RunCompleted。
     */
    private void completeRun(String runId, WorkflowDefinition wf, String endId) {
        boolean transitioned = checkpointStore.update(runId, fresh -> {
            fresh.getNodeOutputs().put(endId,
                    new NodeOutput(endId, aggregatePredecessors(wf, endId, fresh), null, NodeStatus.SUCCEEDED));
            fresh.setUpdatedAt(Instant.now());
            if (fresh.getStatus() != RunStatus.RUNNING) {
                return false; // 已 FAILED / 已 SUCCEEDED，不覆盖、不重复发事件
            }
            fresh.setStatus(RunStatus.SUCCEEDED);
            return true;
        });
        if (transitioned) {
            eventBus.publish(Streams.RUN,
                    codec.toPayload(Events.RunCompleted.of(runId, RunStatus.SUCCEEDED.name(), null)));
            progressBus.publish(RunProgress.runCompleted(runId, RunStatus.SUCCEEDED.name(), null));
        }
    }

    /**
     * END 的最终结果：聚合其直接前驱中执行成功者的输出 {@code {<前驱id>: <输出>}}（QA 37）。
     */
    private static Object aggregatePredecessors(WorkflowDefinition wf, String endId, WorkflowState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (EdgeDefinition edge : wf.getEdges()) {
            if (endId.equals(edge.getTo())) {
                NodeOutput pred = state.getNodeOutputs().get(edge.getFrom());
                if (pred != null && pred.getStatus() == NodeStatus.SUCCEEDED) {
                    result.put(edge.getFrom(), pred.getOutput());
                }
            }
        }
        return result;
    }

    private static String consumerName() {
        return "node-worker-" + System.nanoTime(); // 每实例唯一（QA 76）
    }
}
