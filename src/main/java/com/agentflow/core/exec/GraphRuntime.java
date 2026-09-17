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
import com.agentflow.core.state.NodeOutput;
import com.agentflow.core.state.WorkflowState;
import org.springframework.stereotype.Component;

/**
 * 图调度运行时（T7.3 抽取，QA 79）——从 checkpoint 的事实（节点输出 + 边解析结果）重算就绪集。
 *
 * <p>同步版 WorkflowExecutor 的 GraphState 是内存计数器；事件驱动版把<b>边解析结果</b>持久化进
 * checkpoint（{@code state.resolvedEdges}），就绪/死分支从这些事实重算——不存可变计数器，无并发写问题。
 *
 * <p>用法：node-worker 执行完节点 X 后，在 checkpoint 的 update 里调
 * {@link #resolveOutEdges(String, WorkflowDefinition, WorkflowState, boolean, String)}，返回新就绪的下游节点；
 * 死分支节点记入 {@code state.deadNodes} 并级联（QA 79）。START 由 run-worker 直接发布 NodeReady（无入边，不走本类）。
 *
 * <p><b>并发约定（Bug 08）</b>：解析必须发生在「提交那一刻的最新快照」上（就绪性要从别人的事实里读，
 * 旧快照会导致漏发）；而 {@link #routeDynamic} 会调 LLM，必须提到 CAS 重试循环之外先算好。两者合起来
 * 就是「昂贵且非幂等的算一次、廉价的每次重算」。
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
     * LLM_DYNAMIC 出边路由：调一次 LLM 选出目标（T4.4）。
     *
     * <p><b>必须与 {@link #resolveOutEdges} 分开、在 checkpoint 的 update 之外调用</b>（Bug 08）：
     * 这是整条推导链路里<b>唯一会调 LLM</b> 的一步，放进 CAS 的重试循环里，冲突一次就要重调一次 LLM。
     * 它只依赖「节点自身的输出 + 出边定义」，不依赖其余节点的事实，所以提前算一次是安全的。
     *
     * @param output 该节点的执行输出（尚未写进 state，故由调用方传入）
     * @return 选中的目标节点 id；无 LLM_DYNAMIC 出边或未配置 llmRouter 时返回 null
     */
    public String routeDynamic(String nodeId, WorkflowDefinition wf, Object output) {
        List<EdgeDefinition> dynamicEdges = outEdges(nodeId, wf).stream()
                .filter(e -> e.getType() == EdgeType.LLM_DYNAMIC)
                .toList();
        if (dynamicEdges.isEmpty() || llmRouter == null) {
            return null;
        }
        try {
            return llmRouter.route(dynamicEdges, output);
        } catch (Exception e) {
            throw new IllegalStateException("动态路由失败：" + e.getMessage(), e);
        }
    }

    /**
     * 便捷重载：内部现算动态路由。
     *
     * <p><b>仅供单线程调用方与测试用</b>。并发路径（{@code NodeWorker} 的 checkpoint update mutator 内）
     * 必须用 {@link #resolveOutEdges(String, WorkflowDefinition, WorkflowState, boolean, String)}
     * 并传入提前算好的 {@code dynamicTarget}，否则 CAS 重试会重复调 LLM。
     */
    public List<String> resolveOutEdges(String nodeId, WorkflowDefinition wf, WorkflowState state, boolean allDead) {
        NodeOutput no = state.getNodeOutputs().get(nodeId);
        String dynamicTarget = allDead || no == null ? null : routeDynamic(nodeId, wf, no.getOutput());
        return resolveOutEdges(nodeId, wf, state, allDead, dynamicTarget);
    }

    /**
     * 解析节点 X 的出边并返回新就绪的节点（含死分支级联）。
     *
     * <p><b>本方法会就地改 {@code state}</b>（写 {@code resolvedEdges} / {@code deadNodes}），且必须在
     * checkpoint 的 {@code update} mutator 内、基于 mutator 收到的那份<b>最新快照</b>调用（Bug 08）：
     * 就绪性要从别人的事实里读（{@link #allInEdgesResolved}），拿旧快照算好再搬过去会漏发
     * —— fan-in 节点 Z 的两条入边由两个 worker 各自解析，双方在旧快照里都看不到对方，于是谁都不发 Z。
     * 除动态路由外的部分都不调 LLM，因此可安全重算。
     *
     * @param allDead       X 是死分支节点：其出边一律按死边解析（不做条件/路由求值，QA 79）
     * @param dynamicTarget 预先算好的路由目标（可为 null），避免在 CAS 重试里重调 LLM
     */
    public List<String> resolveOutEdges(String nodeId, WorkflowDefinition wf, WorkflowState state,
                                        boolean allDead, String dynamicTarget) {
        List<EdgeDefinition> outEdges = outEdges(nodeId, wf);
        Map<String, Boolean> resolutions = new LinkedHashMap<>();

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
            ready.addAll(resolveOutEdges(target, wf, state, true, null)); // 死分支全死边，路由无意义
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
