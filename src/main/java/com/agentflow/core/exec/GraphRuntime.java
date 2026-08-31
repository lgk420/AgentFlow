package com.agentflow.core.exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentflow.core.model.EdgeDefinition;
import com.agentflow.core.model.EdgeType;
import com.agentflow.core.model.WorkflowDefinition;
import com.agentflow.core.routing.ConditionEvaluator;
import com.agentflow.core.routing.LlmRouter;
import com.agentflow.core.state.WorkflowState;
import org.springframework.stereotype.Component;

/**
 * 图调度运行时（T7.3 抽取，QA 79）——从 checkpoint 的事实（节点输出 + 边解析结果）重算就绪集。
 *
 * <p>同步版 WorkflowExecutor 的 GraphState 是内存计数器；事件驱动版把<b>边解析结果</b>持久化进
 * checkpoint（{@code state.resolvedEdges}），就绪/死分支从这些事实重算——不存可变计数器，无并发写问题。
 *
 * <p>用法：node-worker 执行完节点 X 后调 {@link #resolveOutEdges(String, WorkflowDefinition, WorkflowState, boolean)}，返回新就绪的
 * 下游节点；死分支节点记入 {@code state.deadNodes} 并级联（QA 79）。START 由 run-worker 直接发布
 * NodeReady（无入边，不走本类）。
 */
@Component
public class GraphRuntime {

    private final ConditionEvaluator conditionEvaluator;
    private final LlmRouter llmRouter;

    public GraphRuntime(ConditionEvaluator conditionEvaluator, LlmRouter llmRouter) {
        this.conditionEvaluator = conditionEvaluator;
        this.llmRouter = llmRouter;
    }

    /**
     * 解析节点 X 的出边并返回新就绪的节点（含死分支级联）。
     *
     * @param allDead X 是死分支节点：其出边一律按死边解析（不做条件/路由求值，QA 79）
     */
    public List<String> resolveOutEdges(String nodeId, WorkflowDefinition wf, WorkflowState state, boolean allDead) {
        List<EdgeDefinition> outEdges = outEdges(nodeId, wf);
        Map<String, Boolean> resolutions = new LinkedHashMap<>();

        String dynamicTarget = null;
        List<EdgeDefinition> dynamicEdges = outEdges.stream()
                .filter(e -> e.getType() == EdgeType.LLM_DYNAMIC).toList();
        if (!allDead && !dynamicEdges.isEmpty() && llmRouter != null) {
            try {
                dynamicTarget = llmRouter.route(dynamicEdges, state.getNodeOutputs().get(nodeId).getOutput());
            } catch (Exception e) {
                throw new IllegalStateException("动态路由失败：" + e.getMessage(), e);
            }
        }

        for (EdgeDefinition edge : outEdges) {
            boolean fires;
            if (allDead) {
                fires = false;
            } else {
                fires = switch (edge.getType()) {
                    case STATIC -> true;
                    case CONDITIONAL -> conditionEvaluator.evaluate(edge.getCondition(), state);
                    case LLM_DYNAMIC -> llmRouter != null && edge.getTo().equals(dynamicTarget);
                };
            }
            resolutions.put(edge.getTo(), fires);
        }
        state.getResolvedEdges().put(nodeId, resolutions);

        List<String> ready = new ArrayList<>();
        for (EdgeDefinition edge : outEdges) {
            checkTarget(edge.getTo(), wf, state, ready);
        }
        return ready;
    }

    /**
     * 目标节点：已执行/已死跳过；所有入边已解析 → 至少一条 fired 则就绪，否则死分支记录 + 级联。
     */
    private void checkTarget(String target, WorkflowDefinition wf, WorkflowState state, List<String> ready) {
        if (state.getNodeOutputs().containsKey(target) || state.getDeadNodes().contains(target)) {
            return;
        }
        if (!allInEdgesResolved(target, wf, state)) {
            return;
        }
        if (anyInEdgeFired(target, wf, state)) {
            ready.add(target);
        } else {
            state.getDeadNodes().add(target);
            ready.addAll(resolveOutEdges(target, wf, state, true));
        }
    }

    /**
     * target 的所有入边都已解析（每个 source 的 resolvedEdges 里都有指向 target 的记录）。
     */
    private boolean allInEdgesResolved(String target, WorkflowDefinition wf, WorkflowState state) {
        for (EdgeDefinition edge : wf.getEdges()) {
            if (target.equals(edge.getTo())) {
                Map<String, Boolean> src = state.getResolvedEdges().get(edge.getFrom());
                if (src == null || !src.containsKey(target)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * target 是否至少一条入边 fired（可达）；无入边的节点（如 START）由 run-worker 直接发布。
     */
    private boolean anyInEdgeFired(String target, WorkflowDefinition wf, WorkflowState state) {
        for (EdgeDefinition edge : wf.getEdges()) {
            if (target.equals(edge.getTo())) {
                Boolean fired = state.getResolvedEdges().get(edge.getFrom()).get(target);
                if (Boolean.TRUE.equals(fired)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<EdgeDefinition> outEdges(String nodeId, WorkflowDefinition wf) {
        return wf.getEdges().stream().filter(e -> nodeId.equals(e.getFrom())).toList();
    }
}
