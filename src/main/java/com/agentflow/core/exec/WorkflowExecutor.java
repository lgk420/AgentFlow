package com.agentflow.core.exec;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import com.agentflow.core.model.EdgeDefinition;
import com.agentflow.core.model.EdgeType;
import com.agentflow.core.model.NodeDefinition;
import com.agentflow.core.model.NodeType;
import com.agentflow.core.model.WorkflowDefinition;
import com.agentflow.core.routing.ConditionEvaluator;
import com.agentflow.core.routing.LlmRouter;
import com.agentflow.core.state.NodeOutput;
import com.agentflow.core.state.NodeStatus;
import com.agentflow.core.state.RunStatus;
import com.agentflow.core.state.WorkflowState;
import com.agentflow.runtime.checkpoint.CheckpointStore;
import org.springframework.stereotype.Component;

/**
 * 就绪度调度器（架构 6.1，对标 Spark stage 调度）——wave 批派发（QA 39）。
 *
 * <p>每轮把<b>整个就绪集</b>作为一个 wave 并发派发到 {@link ParallelDispatcher}，等全部完成后
 * 由调度线程<b>顺序</b>处理结果（写 nodeOutputs → 出边求值 → 下一 wave 就绪集）。
 *
 * <p><b>出边求值（T2.4 引入 CONDITIONAL）</b>——上游完成后解析其所有出边（死分支传播，见 T2.4）：
 * <pre>
 * STATIC        → 目标 pending-1，标记 reached
 * CONDITIONAL 真 → 目标 pending-1，标记 reached
 * CONDITIONAL 假 → 目标 pending-1（死边），不标 reached
 * LLM_DYNAMIC   → LlmRouter 选一条（T4.4）：选中边 reached，其余候选死边；无 LlmRouter 时全死边
 * 目标 pending 归零：≥1 边 reached → 下一 wave 派发；0 边 reached → 死分支，不跑，其出边按死边级联传播
 * </pre>
 *
 * <p>并发安全不变量（QA 39）：节点执行只读 state、返回结果、<b>不写</b> state；写 state 收敛到调度线程批后。
 * END 聚合其<b>直接前驱</b>（实际执行过的）输出作为最终结果（QA 37）。
 * 失败一律返回 FAILED 并携带 {@code state.error}（运行级原因，T2.5）：节点失败 / 条件求值失败 /
 * 停滞（就绪集空未到 END，报告未就绪节点）。
 */
@Component
public class WorkflowExecutor {

    private final CheckpointStore checkpointStore;
    private final ParallelDispatcher dispatcher;
    private final ConditionEvaluator conditionEvaluator;
    private final LlmRouter llmRouter;
    private final Map<NodeType, NodeExecutor> executors;

    /**
     * @param llmRouter LLM_DYNAMIC 路由（T4.4）；null 时 LLM_DYNAMIC 边按死边（测试/降级用）
     */
    public WorkflowExecutor(CheckpointStore checkpointStore, ParallelDispatcher dispatcher,
                           ConditionEvaluator conditionEvaluator, List<NodeExecutor> executors,
                           LlmRouter llmRouter) {
        this.checkpointStore = checkpointStore;
        this.dispatcher = dispatcher;
        this.conditionEvaluator = conditionEvaluator;
        this.llmRouter = llmRouter;
        this.executors = new EnumMap<>(NodeType.class);
        executors.forEach(ex -> this.executors.put(ex.type(), ex));
    }

    /**
     * 执行工作流，返回最终状态（含全部 nodeOutputs）。
     */
    public WorkflowState execute(String runId, WorkflowDefinition wf, Map<String, Object> inputs) {
        WorkflowState state = new WorkflowState();
        state.setRunId(runId);
        state.setWorkflowId(wf.getId());
        state.setInputs(inputs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(inputs));
        state.setCreatedAt(Instant.now());
        state.setUpdatedAt(Instant.now());
        checkpointStore.create(state);

        GraphState graph = new GraphState();
        Map<String, List<String>> inEdges = new HashMap<>();
        for (String id : wf.getNodes().keySet()) {
            graph.outEdges.put(id, new ArrayList<>());
            graph.pendingCount.put(id, 0);
            inEdges.put(id, new ArrayList<>());
        }
        for (EdgeDefinition edge : wf.getEdges()) {
            if (edge.getFrom() != null && wf.getNodes().containsKey(edge.getFrom())) {
                graph.outEdges.get(edge.getFrom()).add(edge);
            }
            if (edge.getTo() != null && wf.getNodes().containsKey(edge.getTo())) {
                inEdges.get(edge.getTo()).add(edge.getFrom());
                graph.pendingCount.merge(edge.getTo(), 1, Integer::sum);
            }
        }

        String startId = wf.getNodes().entrySet().stream()
                .filter(en -> en.getValue().getType() == NodeType.START)
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
        if (startId == null) {
            return fail(state, "缺少 START 节点");
        }

        graph.ready.add(startId);
        while (!graph.ready.isEmpty()) {
            List<String> batch = new ArrayList<>(graph.ready);
            graph.ready.clear();

            List<NodeExecutionResult> results = dispatcher.runAll(batch.stream()
                    .map(nodeId -> (Callable<NodeExecutionResult>) () -> executeNode(nodeId, wf, state))
                    .toList());

            boolean endReached = false;
            for (NodeExecutionResult r : results) {
                if (r.error != null) {
                    state.getNodeOutputs().put(r.nodeId,
                            new NodeOutput(r.nodeId, null, r.error.getMessage(), NodeStatus.FAILED));
                    return fail(state, "节点执行失败：" + r.nodeId + "：" + r.error.getMessage());
                }
                if (r.node.getType() == NodeType.END) {
                    Object result = aggregatePredecessors(r.nodeId, state, inEdges);
                    state.getNodeOutputs().put(r.nodeId,
                            new NodeOutput(r.nodeId, result, null, NodeStatus.SUCCEEDED));
                    endReached = true;
                    continue; // END 无出边，继续记录同批其余节点输出
                }
                state.getNodeOutputs().put(r.nodeId,
                        new NodeOutput(r.nodeId, r.output, null, NodeStatus.SUCCEEDED));
                persist(state);

                List<EdgeDefinition> outEdges = graph.outEdges.getOrDefault(r.nodeId, List.of());
                // LLM_DYNAMIC（T4.4）：若存在，先调 LlmRouter 一次拿选中目标，选中边触发、其余候选死分支
                String dynamicTarget = null;
                List<EdgeDefinition> dynamicEdges = outEdges.stream()
                        .filter(e -> e.getType() == EdgeType.LLM_DYNAMIC).toList();
                if (!dynamicEdges.isEmpty() && llmRouter != null) {
                    try {
                        dynamicTarget = llmRouter.route(dynamicEdges, r.output);
                    } catch (Exception e) {
                        state.getNodeOutputs().put(r.nodeId, new NodeOutput(r.nodeId, null,
                                "动态路由失败：" + e.getMessage(), NodeStatus.FAILED));
                        return fail(state, "动态路由失败：" + e.getMessage());
                    }
                }
                for (EdgeDefinition edge : outEdges) {
                    boolean fires;
                    try {
                        fires = switch (edge.getType()) {
                            case STATIC -> true;
                            case CONDITIONAL -> conditionEvaluator.evaluate(edge.getCondition(), state);
                            case LLM_DYNAMIC -> llmRouter != null && edge.getTo().equals(dynamicTarget);
                        };
                    } catch (Exception e) {
                        state.getNodeOutputs().put(r.nodeId, new NodeOutput(r.nodeId, null,
                                "条件求值失败：" + edge.getCondition() + "：" + e.getMessage(), NodeStatus.FAILED));
                        return fail(state, "条件求值失败：" + edge.getCondition());
                    }
                    graph.resolve(fires, edge.getTo());
                }
            }
            if (endReached) {
                return finish(state, RunStatus.SUCCEEDED);
            }
        }

        // 就绪集空但未到 END：停滞（T2.5 死锁/进度检测）。未就绪节点 = pendingCount 未归零者
        List<String> blocked = graph.pendingCount.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        String stallMsg = "进度停滞：无法到达 END";
        if (!blocked.isEmpty()) {
            stallMsg += "，未就绪节点：" + blocked;
        }
        return fail(state, stallMsg);
    }

    /**
     * 并发任务体：执行节点并产出结果；异常在此捕获（不抛给线程池）。
     * END 不进执行器表（调度器批后聚合处理），直接返回占位结果。
     */
    private NodeExecutionResult executeNode(String nodeId, WorkflowDefinition wf, WorkflowState state) {
        NodeDefinition node = wf.getNodes().get(nodeId);
        if (node.getType() == NodeType.END) {
            return new NodeExecutionResult(nodeId, node, null, null);
        }
        NodeExecutor executor = executors.get(node.getType());
        if (executor == null) {
            return new NodeExecutionResult(nodeId, node, null,
                    new IllegalStateException("节点类型未实现：" + node.getType()));
        }
        // 节点内重试（T2.6）：按 retryPolicy（次数/退避）重试，耗尽才判失败（架构 6.2）
        RetryPolicy policy = RetryPolicy.fromConfig(node.getConfig());
        int maxAttempts = policy.getRetries() + 1;
        int attempt = 0;
        Throwable lastError = null;
        while (attempt < maxAttempts) {
            try {
                Object output = executor.execute(node, state);
                return new NodeExecutionResult(nodeId, node, output, null);
            } catch (Exception e) {
                lastError = e;
                attempt++;
                if (attempt < maxAttempts) {
                    sleepQuietly(policy.getBackoffMs());
                }
            }
        }
        return new NodeExecutionResult(nodeId, node, null, lastError);
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
     * END 最终结果：聚合实际执行成功的直接前驱输出 {@code {<前驱id>: <输出>, ...}}（QA 37）。
     */
    private Object aggregatePredecessors(String endId, WorkflowState state, Map<String, List<String>> inEdges) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String pred : inEdges.getOrDefault(endId, List.of())) {
            NodeOutput no = state.getNodeOutputs().get(pred);
            if (no != null && no.getStatus() == NodeStatus.SUCCEEDED) {
                result.put(pred, no.getOutput());
            }
        }
        return result;
    }

    private WorkflowState finish(WorkflowState state, RunStatus status) {
        state.setStatus(status);
        state.setUpdatedAt(Instant.now());
        persist(state);
        return state;
    }

    /**
     * 把本地 state 镜像进 checkpoint（同步路径专用）。
     *
     * <p>同步路径由调度线程独占该 run、不存在并发写，所以这里用 {@code update} 把本地快照整体镜像过去，
     * 而不是逐字段增量。事件驱动的并发写走 {@code NodeWorker}，那里是真正的增量提交（Bug 08）。
     */
    private void persist(WorkflowState state) {
        checkpointStore.update(state.getRunId(), fresh -> {
            fresh.setStatus(state.getStatus());
            fresh.setError(state.getError());
            fresh.setUpdatedAt(state.getUpdatedAt());
            fresh.setNodeOutputs(new LinkedHashMap<>(state.getNodeOutputs()));
            fresh.setResolvedEdges(new LinkedHashMap<>(state.getResolvedEdges()));
            fresh.setDeadNodes(new LinkedHashSet<>(state.getDeadNodes()));
            return null;
        });
    }

    /**
     * FAILED 收尾：写入运行级失败原因后标记 FAILED（T2.5）。
     */
    private WorkflowState fail(WorkflowState state, String error) {
        state.setError(error);
        return finish(state, RunStatus.FAILED);
    }

    /**
     * 单节点并行执行结果（只在调度线程读，见 QA 40）。
     */
    private static final class NodeExecutionResult {
        /**
         * 节点 id。
         */
        final String nodeId;

        /**
         * 节点定义（调度线程判断 END / 查出边用）。
         */
        final NodeDefinition node;

        /**
         * 执行输出；失败时为 null。
         */
        final Object output;

        /**
         * 执行异常；成功时为 null（error 是否为 null 隐含成败，status 由调度线程决定）。
         */
        final Throwable error;

        NodeExecutionResult(String nodeId, NodeDefinition node, Object output, Throwable error) {
            this.nodeId = nodeId;
            this.node = node;
            this.output = output;
            this.error = error;
        }
    }

    /**
     * 单次运行的图调度状态（入边计数 + reached 标记 + 就绪集）与出边解析逻辑。
     *
     * <p>死分支传播（T2.4）：节点 pendingCount 归零但没有任何入边 reached → 死分支不执行，
     * 其出边按死边级联解析（下游无需等待它）。
     */
    private static final class GraphState {
        /**
         * 出边：from → 出边列表。
         */
        final Map<String, List<EdgeDefinition>> outEdges = new HashMap<>();

        /**
         * 入边计数：每条入边（含 CONDITIONAL）计 1，出边解析时递减；归零即所有入边已解析。
         */
        final Map<String, Integer> pendingCount = new HashMap<>();

        /**
         * 已 reached 的节点（至少一条入边触发）。
         */
        final Set<String> reached = new HashSet<>();

        /**
         * 下一 wave 的就绪集。
         */
        final Deque<String> ready = new ArrayDeque<>();

        /**
         * 解析一条入边对 target 的影响；fires=false 表示该边是死边（不 reached）。
         */
        void resolve(boolean fires, String target) {
            if (!pendingCount.containsKey(target)) {
                return; // 防御：target 不在图中
            }
            if (fires) {
                reached.add(target);
            }
            Integer remain = pendingCount.computeIfPresent(target, (k, v) -> v - 1);
            if (remain != null && remain == 0) {
                if (reached.contains(target)) {
                    ready.add(target);
                } else {
                    resolveDead(target);
                }
            }
        }

        /**
         * 死节点：不执行，其出边一律按死边解析（级联传播）。
         */
        private void resolveDead(String nodeId) {
            for (EdgeDefinition edge : outEdges.getOrDefault(nodeId, List.of())) {
                resolve(false, edge.getTo());
            }
        }
    }
}
