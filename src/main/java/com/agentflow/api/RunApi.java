package com.agentflow.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

import com.agentflow.core.model.WorkflowDefinition;
import com.agentflow.core.state.RunStatus;
import com.agentflow.core.state.WorkflowState;
import com.agentflow.core.store.WorkflowStore;
import com.agentflow.runtime.checkpoint.CheckpointStore;
import com.agentflow.runtime.queue.EventBus;
import com.agentflow.runtime.queue.EventCodec;
import com.agentflow.runtime.queue.Events;
import com.agentflow.runtime.queue.Streams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行 API（T7.3 事件驱动版）。
 *
 * <p>POST /runs 不再同步执行：<b>写初始 checkpoint（RUNNING）+ publish RunStarted</b> → 轮询
 * checkpoint 到终态返回（对外形态不变，同步阻塞的语义保留）。执行由 run-worker / node-worker
 * 消费事件驱动（T7.3）。
 */
@RestController
@RequestMapping("/api/v1/runs")
public class RunApi {

    private final WorkflowStore workflowStore;
    private final CheckpointStore checkpointStore;
    private final EventBus eventBus;
    private final EventCodec codec;

    @Value("${core.run.poll-timeout-ms:60000}")
    private long pollTimeoutMs;

    @Value("${core.run.poll-interval-ms:100}")
    private long pollIntervalMs;

    public RunApi(WorkflowStore workflowStore, CheckpointStore checkpointStore,
                  EventBus eventBus, EventCodec codec) {
        this.workflowStore = workflowStore;
        this.checkpointStore = checkpointStore;
        this.eventBus = eventBus;
        this.codec = codec;
    }

    /**
     * 提交一次运行：取图（404 无）→ 写初始 checkpoint → publish RunStarted → 轮询到终态返回。
     */
    @PostMapping
    public ResponseEntity<WorkflowState> create(@RequestBody RunRequest request) {
        WorkflowDefinition wf = workflowStore.findById(request.getWorkflowId());
        if (wf == null) {
            return ResponseEntity.notFound().build();
        }
        String runId = UUID.randomUUID().toString();

        WorkflowState state = new WorkflowState();
        state.setRunId(runId);
        state.setWorkflowId(wf.getId());
        state.setInputs(request.getInputs() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getInputs()));
        state.setStatus(RunStatus.RUNNING);
        state.setCreatedAt(Instant.now());
        state.setUpdatedAt(Instant.now());
        checkpointStore.save(state);

        eventBus.publish(Streams.RUN, codec.toPayload(Events.RunStarted.of(runId, wf.getId(), state.getInputs())));

        return pollUntilTerminal(runId);
    }

    /**
     * 轮询 checkpoint 直到非 RUNNING（SUCCEEDED/FAILED）或超时；对外保留"阻塞返回最终状态"的语义。
     */
    private ResponseEntity<WorkflowState> pollUntilTerminal(String runId) {
        long deadline = System.currentTimeMillis() + pollTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            WorkflowState current = checkpointStore.load(runId);
            if (current != null && current.getStatus() != RunStatus.RUNNING) {
                return ResponseEntity.ok(current);
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ResponseEntity.ok(checkpointStore.load(runId));
            }
        }
        return ResponseEntity.ok(checkpointStore.load(runId));
    }

    /**
     * 查询运行状态（含 nodeOutputs / status / error）；不存在 404。
     */
    @GetMapping("/{runId}")
    public ResponseEntity<WorkflowState> get(@PathVariable String runId) {
        WorkflowState state = checkpointStore.load(runId);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(state);
    }
}
