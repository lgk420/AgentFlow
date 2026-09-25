package com.agentflow.runtime.checkpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.agentflow.engine.model.state.NodeOutput;
import com.agentflow.engine.model.state.NodeStatus;
import com.agentflow.engine.model.state.RunStatus;
import com.agentflow.engine.model.state.WorkflowState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T2.1 InMemoryCheckpointStore 验收测试。
 *
 * <p>覆盖：create/load 往返一致；create 存快照（改原 state 不影响已存）；load 返回快照（改返回值不影响存储）；
 * 不存在的 runId 返回 null；create 拒绝覆盖已存在的 run。
 *
 * <p>外加 <b>Bug 08 并发写回归</b>：多线程并发 update 同一 run 时，各自产生的事实都必须保留
 * （旧实现是整份覆盖写，会互相覆盖丢失更新）。
 */
class InMemoryCheckpointStoreTest {

    private final InMemoryCheckpointStore store = new InMemoryCheckpointStore();

    @Test
    void createAndLoad_roundTrip() {
        WorkflowState s = new WorkflowState();
        s.setRunId("r1");
        s.setStatus(RunStatus.SUCCEEDED);
        s.getInputs().put("userMessage", "hi");
        s.getNodeOutputs().put("classify",
                new NodeOutput("classify", "output-data", null, NodeStatus.SUCCEEDED));

        store.create(s);
        WorkflowState loaded = store.load("r1");

        assertThat(loaded).isNotNull();
        assertThat(loaded.getRunId()).isEqualTo("r1");
        assertThat(loaded.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(loaded.getInputs()).containsEntry("userMessage", "hi");
        assertThat(loaded.getNodeOutputs().get("classify").getOutput()).isEqualTo("output-data");
    }

    @Test
    void create_isolatesFromCallerMutation() {
        WorkflowState s = new WorkflowState();
        s.setRunId("r1");
        s.getInputs().put("a", "1");
        store.create(s);

        s.getInputs().put("b", "2"); // 保存后再改原对象

        assertThat(store.load("r1").getInputs()).doesNotContainKey("b");
    }

    @Test
    void load_returnsSnapshot_isolatedFromStore() {
        WorkflowState s = new WorkflowState();
        s.setRunId("r1");
        s.getInputs().put("a", "1");
        store.create(s);

        WorkflowState loaded = store.load("r1");
        loaded.getInputs().put("z", "9"); // 改返回值

        assertThat(store.load("r1").getInputs()).doesNotContainKey("z");
    }

    @Test
    void load_missing_returnsNull() {
        assertThat(store.load("nope")).isNull();
    }

    @Test
    void create_existingRun_isRejected() {
        WorkflowState s = new WorkflowState();
        s.setRunId("r1");
        store.create(s);

        WorkflowState again = new WorkflowState();
        again.setRunId("r1");
        assertThatThrownBy(() -> store.create(again)).isInstanceOf(IllegalStateException.class);
    }

    /**
     * Bug 08 回归：并发写不丢更新。
     *
     * <p>N 个写者并发对同一 run 提交各自「独有的节点输出」，断言最终 N 份事实一份不少。
     * 旧实现（整份覆盖 save + 读-改-写）在这里必然丢——后提交者用自己那份旧快照覆盖掉先提交者的输出。
     */
    @Test
    void update_concurrentWriters_keepAllFacts() throws Exception {
        WorkflowState initial = new WorkflowState();
        initial.setRunId("r1");
        store.create(initial);

        int writers = 4;
        int perWriter = 25;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int writerIndex = w;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < perWriter; i++) {
                        String nodeId = "node-" + writerIndex + "-" + i;
                        store.update("r1", fresh -> {
                            fresh.getNodeOutputs().put(nodeId,
                                    new NodeOutput(nodeId, nodeId + "-out", null, NodeStatus.SUCCEEDED));
                            return null;
                        });
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        WorkflowState finalState = store.load("r1");
        assertThat(finalState.getNodeOutputs()).hasSize(writers * perWriter);
        assertThat(finalState.getVersion()).isEqualTo(writers * perWriter);
    }
}
