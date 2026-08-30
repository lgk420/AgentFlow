package com.agentflow.api;

import java.util.UUID;

import com.agentflow.core.exec.WorkflowExecutor;
import com.agentflow.core.model.WorkflowDefinition;
import com.agentflow.core.state.WorkflowState;
import com.agentflow.core.store.WorkflowStore;
import com.agentflow.runtime.checkpoint.CheckpointStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行 API（T2.7）。
 *
 * <p>P2 同步执行：POST 阻塞跑完直接返回最终状态；GET 从 {@link CheckpointStore} 查运行状态
 * （executor 执行时已按 runId 存 checkpoint）。P7 改事件驱动（异步）时本接口对外形态不变。
 */
@RestController
@RequestMapping("/api/v1/runs")
public class RunApi {

    private final WorkflowStore workflowStore;
    private final WorkflowExecutor executor;
    private final CheckpointStore checkpointStore;

    public RunApi(WorkflowStore workflowStore, WorkflowExecutor executor, CheckpointStore checkpointStore) {
        this.workflowStore = workflowStore;
        this.executor = executor;
        this.checkpointStore = checkpointStore;
    }

    /**
     * 提交一次运行：取图定义（404 无）→ 生成 runId → 同步执行 → 返回最终状态。
     */
    @PostMapping
    public ResponseEntity<WorkflowState> create(@RequestBody RunRequest request) {
        WorkflowDefinition wf = workflowStore.findById(request.getWorkflowId());
        if (wf == null) {
            return ResponseEntity.notFound().build();
        }
        String runId = UUID.randomUUID().toString();
        WorkflowState state = executor.execute(runId, wf, request.getInputs());
        return ResponseEntity.ok(state);
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
