package com.agentflow.runtime.checkpoint;

import com.agentflow.core.state.NodeOutput;
import com.agentflow.core.state.NodeStatus;
import com.agentflow.core.state.RunStatus;
import com.agentflow.core.state.WorkflowState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2.1 InMemoryCheckpointStore 验收测试。
 *
 * <p>覆盖：save/load 往返一致；save 存快照（改原 state 不影响已存）；load 返回快照（改返回值不影响存储）；
 * 不存在的 runId 返回 null。
 */
class InMemoryCheckpointStoreTest {

    private final InMemoryCheckpointStore store = new InMemoryCheckpointStore();

    @Test
    void saveAndLoad_roundTrip() {
        WorkflowState s = new WorkflowState();
        s.setRunId("r1");
        s.setStatus(RunStatus.SUCCEEDED);
        s.getInputs().put("userMessage", "hi");
        s.getNodeOutputs().put("classify",
                new NodeOutput("classify", "output-data", null, NodeStatus.SUCCEEDED));

        store.save(s);
        WorkflowState loaded = store.load("r1");

        assertThat(loaded).isNotNull();
        assertThat(loaded.getRunId()).isEqualTo("r1");
        assertThat(loaded.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(loaded.getInputs()).containsEntry("userMessage", "hi");
        assertThat(loaded.getNodeOutputs().get("classify").getOutput()).isEqualTo("output-data");
    }

    @Test
    void save_isolatesFromCallerMutation() {
        WorkflowState s = new WorkflowState();
        s.setRunId("r1");
        s.getInputs().put("a", "1");
        store.save(s);

        s.getInputs().put("b", "2"); // 保存后再改原对象

        assertThat(store.load("r1").getInputs()).doesNotContainKey("b");
    }

    @Test
    void load_returnsSnapshot_isolatedFromStore() {
        WorkflowState s = new WorkflowState();
        s.setRunId("r1");
        s.getInputs().put("a", "1");
        store.save(s);

        WorkflowState loaded = store.load("r1");
        loaded.getInputs().put("z", "9"); // 改返回值

        assertThat(store.load("r1").getInputs()).doesNotContainKey("z");
    }

    @Test
    void load_missing_returnsNull() {
        assertThat(store.load("nope")).isNull();
    }
}
