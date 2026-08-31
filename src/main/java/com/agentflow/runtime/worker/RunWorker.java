package com.agentflow.runtime.worker;

import java.util.List;
import java.util.Map;

import com.agentflow.core.model.NodeType;
import com.agentflow.core.model.WorkflowDefinition;
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
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 运行处理器（T7.3）——消费 {@link Streams#RUN} 的 RunStarted：加载工作流 → 发布首个 NodeReady(start)。
 * workflow 不存在 / 缺 START → 运行直接 FAILED（发 RunCompleted）。
 *
 * <p>失败不 ACK（留在 PEL 重投，at-least-once）；幂等由 checkpoint 事实兜底（T7.4 正式幂等键）。
 */
@Component
@ConditionalOnProperty(name = "core.event-driven.enabled", havingValue = "true", matchIfMissing = true)
public class RunWorker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RunWorker.class);

    private final EventBus eventBus;
    private final EventCodec codec;
    private final CheckpointStore checkpointStore;
    private final WorkflowStore workflowStore;

    public RunWorker(EventBus eventBus, EventCodec codec, CheckpointStore checkpointStore,
                     WorkflowStore workflowStore) {
        this.eventBus = eventBus;
        this.codec = codec;
        this.checkpointStore = checkpointStore;
        this.workflowStore = workflowStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread worker = new Thread(this::loop, "run-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void loop() {
        while (true) {
            List<EventMessage> events;
            try {
                events = eventBus.read(Streams.RUN, Streams.RUN_WORKER, consumerName(), 10, 2000);
            } catch (Exception e) {
                log.warn("run 事件读取失败，重试", e);
                continue;
            }
            for (EventMessage event : events) {
                try {
                    process(event);
                    eventBus.ack(Streams.RUN, Streams.RUN_WORKER, event.id());
                } catch (Exception e) {
                    log.warn("RunStarted 处理失败，等待重投: {} error={}", event.id(), e.getMessage());
                }
            }
        }
    }

    private void process(EventMessage event) {
        // 只处理 RunStarted；RunCompleted 等同流事件直接确认跳过（否则会把已完成的 run 误判 FAILED）
        if (!Events.RunStarted.TYPE.equals(event.payload().get(Events.TYPE_FIELD))) {
            return;
        }
        String runId = (String) event.payload().get("runId");
        String workflowId = (String) event.payload().get("workflowId");

        WorkflowDefinition wf = workflowStore.findById(workflowId);
        if (wf == null) {
            failRun(runId, "工作流不存在: " + workflowId);
            return;
        }
        String startId = wf.getNodes().entrySet().stream()
                .filter(e -> e.getValue().getType() == NodeType.START)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        if (startId == null) {
            failRun(runId, "缺少 START 节点");
            return;
        }
        eventBus.publish(Streams.NODE, codec.toPayload(Events.NodeReady.of(runId, startId)));
    }

    private void failRun(String runId, String error) {
        WorkflowState state = checkpointStore.load(runId);
        if (state == null) {
            return;
        }
        state.setError(error);
        state.setStatus(RunStatus.FAILED);
        checkpointStore.save(state);
        eventBus.publish(Streams.RUN, codec.toPayload(Events.RunCompleted.of(runId, RunStatus.FAILED.name(), error)));
    }

    private static String consumerName() {
        return "run-worker-" + System.nanoTime(); // 每实例唯一（QA 76：consumer 名唯一才能正确记 PEL）
    }
}
