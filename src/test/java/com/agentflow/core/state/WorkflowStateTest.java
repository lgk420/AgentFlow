package com.agentflow.core.state;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2.1 WorkflowState 验收测试。
 *
 * <p>覆盖：inputs / nodeOutputs / status 读写；快照深拷贝隔离（改原对象不影响快照、改快照不影响原对象）；
 * NodeOutput 默认状态；版本字段读写。
 */
class WorkflowStateTest {

    @Test
    void stateReadWrite() {
        WorkflowState s = new WorkflowState();
        s.setRunId("r1");
        s.setWorkflowId("fitness-coach");
        s.setStatus(RunStatus.RUNNING);
        s.setVersion(3);
        s.setCreatedAt(Instant.parse("2026-08-15T10:00:00Z"));
        s.setUpdatedAt(Instant.parse("2026-08-15T10:00:01Z"));
        s.getInputs().put("userMessage", "我想练背");
        s.getNodeOutputs().put("classify",
                new NodeOutput("classify", Map.of("category", "ANALYSIS"), null, NodeStatus.SUCCEEDED));

        assertThat(s.getRunId()).isEqualTo("r1");
        assertThat(s.getWorkflowId()).isEqualTo("fitness-coach");
        assertThat(s.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(s.getVersion()).isEqualTo(3);
        assertThat(s.getInputs()).containsEntry("userMessage", "我想练背");
        NodeOutput out = s.getNodeOutputs().get("classify");
        assertThat(out.getNodeId()).isEqualTo("classify");
        assertThat(out.getStatus()).isEqualTo(NodeStatus.SUCCEEDED);
        assertThat(out.getOutput()).isEqualTo(Map.of("category", "ANALYSIS"));
    }

    @Test
    void stateDefaultStatus_isRunning() {
        assertThat(new WorkflowState().getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void snapshot_isolatesMapStructure() {
        WorkflowState s = new WorkflowState();
        s.getInputs().put("a", "1");
        s.getNodeOutputs().put("n", new NodeOutput("n", "out", null, NodeStatus.SUCCEEDED));

        WorkflowState snap = s.snapshot();

        // 改原对象：不影响快照
        s.getInputs().put("b", "2");
        s.getNodeOutputs().put("m", new NodeOutput("m", "x", null, NodeStatus.SUCCEEDED));
        assertThat(snap.getInputs()).doesNotContainKey("b");
        assertThat(snap.getNodeOutputs()).doesNotContainKey("m");

        // 改快照：不影响原对象
        snap.getInputs().put("c", "3");
        assertThat(s.getInputs()).doesNotContainKey("c");
    }

    @Test
    void snapshot_copiesNodeOutputRecord() {
        WorkflowState s = new WorkflowState();
        s.getNodeOutputs().put("n", new NodeOutput("n", "out", null, NodeStatus.SUCCEEDED));

        WorkflowState snap = s.snapshot();
        // 快照里的 NodeOutput 是独立对象，改它不影响原对象
        snap.getNodeOutputs().get("n").setStatus(NodeStatus.FAILED);
        assertThat(s.getNodeOutputs().get("n").getStatus()).isEqualTo(NodeStatus.SUCCEEDED);
    }
}
