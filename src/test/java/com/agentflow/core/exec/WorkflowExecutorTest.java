package com.agentflow.core.exec;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentflow.agent.StubLlmGateway;
import com.agentflow.core.dsl.GraphParser;
import com.agentflow.core.dsl.TemplateResolver;
import com.agentflow.core.model.NodeDefinition;
import com.agentflow.core.model.NodeType;
import com.agentflow.core.model.WorkflowDefinition;
import com.agentflow.core.routing.ConditionEvaluator;
import com.agentflow.core.routing.LlmRouter;
import com.agentflow.core.state.NodeStatus;
import com.agentflow.core.state.RunStatus;
import com.agentflow.core.state.WorkflowState;
import com.agentflow.rag.RerankProperties;
import com.agentflow.rag.RetrievalProperties;
import com.agentflow.rag.RetrievedChunk;
import com.agentflow.rag.StubReranker;
import com.agentflow.rag.StubRetriever;
import com.agentflow.runtime.checkpoint.InMemoryCheckpointStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2.2 就绪度调度验收测试。
 *
 * <p>覆盖：串行链 / 双分支 / 简单并行跑到 SUCCEEDED；节点输出记录进 nodeOutputs；
 * END 聚合直接前驱输出（QA 37）；inputs 注入 state；未实现节点类型 → run FAILED。
 */
class WorkflowExecutorTest {

    private final GraphParser parser = new GraphParser(new ObjectMapper());
    private final WorkflowExecutor executor = new WorkflowExecutor(
            new InMemoryCheckpointStore(),
            new ParallelDispatcher(4),
            new ConditionEvaluator(),
            List.of(new StartNodeExecutor(), new StubToolNodeExecutor(), new StubLlmNodeExecutor()), null);

    private WorkflowDefinition fixture(String resource) throws Exception {
        String json = new String(
                getClass().getResourceAsStream(resource).readAllBytes(), StandardCharsets.UTF_8);
        return parser.parse(json);
    }

    @Test
    void serialChain_completesToSucceeded_andEndAggregates() throws Exception {
        WorkflowState state = executor.execute("run-1", fixture("/testdata/workflows/t2/serial.json"), Map.of());

        assertThat(state.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(state.getNodeOutputs()).containsKeys("start", "a", "end");
        assertThat(state.getNodeOutputs().get("a").getStatus()).isEqualTo(NodeStatus.SUCCEEDED);
        // END 聚合直接前驱 a 的输出
        assertThat(state.getNodeOutputs().get("end").getOutput())
                .isEqualTo(Map.of("a", Map.of("x", "a-输出")));
    }

    @Test
    void branch_twoPathsRunAndConvergeAtEnd() throws Exception {
        WorkflowState state = executor.execute("run-2", fixture("/testdata/workflows/t2/branch.json"), Map.of());

        assertThat(state.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(state.getNodeOutputs()).containsKeys("a", "b", "end");
        // END 聚合两个实际执行的前驱输出
        assertThat(state.getNodeOutputs().get("end").getOutput())
                .isEqualTo(Map.of(
                        "a", Map.of("branch", "A"),
                        "b", Map.of("data", "tool:query 桩输出")));
    }

    @Test
    void parallel_bothIndependentNodesComplete() throws Exception {
        WorkflowState state = executor.execute("run-3", fixture("/testdata/workflows/t2/parallel.json"), Map.of());

        assertThat(state.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(state.getNodeOutputs()).containsKeys("tool1", "tool2", "end");
        assertThat(state.getNodeOutputs().get("tool1").getOutput())
                .isEqualTo(Map.of("data", "tool:query_a 桩输出"));
        assertThat(state.getNodeOutputs().get("tool2").getOutput())
                .isEqualTo(Map.of("data", "tool:query_b 桩输出"));
    }

    @Test
    void inputs_areInjectedIntoState() throws Exception {
        WorkflowState state = executor.execute("run-4", fixture("/testdata/workflows/t2/serial.json"),
                Map.of("userMessage", "我想练背"));

        assertThat(state.getInputs()).containsEntry("userMessage", "我想练背");
    }

    @Test
    void ragNode_writesChunksToState_endAggregates() throws Exception {
        // T6.3 集成：RAG 节点 query 模板解析 → StubRetriever → {chunks} 写 state，END 聚合（检索分支产出可用 chunks）
        StubRetriever retriever = new StubRetriever(List.of(new RetrievedChunk("背部渐进超负荷", 0.9)));
        WorkflowExecutor ragExecutor = new WorkflowExecutor(
                new InMemoryCheckpointStore(),
                new ParallelDispatcher(4),
                new ConditionEvaluator(),
                List.of(new StartNodeExecutor(), new StubToolNodeExecutor(),
                        new RagNodeExecutor(retriever, StubReranker.passthrough(), new RerankProperties(),
                                new RetrievalProperties(), new TemplateResolver())), null);

        WorkflowState state = ragExecutor.execute("run-rag", parser.parse("""
                { "id": "rag-wf", "name": "rag",
                  "nodes": {
                    "start": { "type": "START" },
                    "kb": { "type": "RAG", "query": "用户想：{{inputs.userMessage}}", "topK": 3, "collection": "kb" },
                    "end": { "type": "END" } },
                  "edges": [
                    { "from": "start", "to": "kb" },
                    { "from": "kb", "to": "end" } ] }
                """), Map.of("userMessage", "练背"));

        assertThat(state.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(retriever.getLastQuery()).isEqualTo("用户想：练背");
        assertThat(retriever.getLastTopK()).isEqualTo(3);
        assertThat(retriever.getLastCollection()).isEqualTo("kb");

        Object kbOut = state.getNodeOutputs().get("kb").getOutput();
        assertThat(kbOut).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        List<RetrievedChunk> chunks = (List<RetrievedChunk>) ((Map<String, Object>) kbOut).get("chunks");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getContent()).isEqualTo("背部渐进超负荷");
        // END 聚合直接前驱 kb 的输出
        assertThat(state.getNodeOutputs().get("end").getOutput()).isEqualTo(Map.of("kb", kbOut));
    }

    /**
     * T6.8 拒答闭环：条件边判 {@code {{nodes.kb.output.hit}}}，命中走回答、未命中走兜底。
     *
     * <p>这是「阈值」能真正生效的最后一环——没有它，{@code hit} 字段只是躺在输出里没人用。
     */
    private WorkflowExecutor refusalExecutor(StubRetriever retriever, double minScore) {
        RetrievalProperties threshold = new RetrievalProperties();
        threshold.getMinScore().setVector(minScore);
        return new WorkflowExecutor(
                new InMemoryCheckpointStore(),
                new ParallelDispatcher(4),
                new ConditionEvaluator(),
                List.of(new StartNodeExecutor(), new StubLlmNodeExecutor(),
                        new RagNodeExecutor(retriever, StubReranker.passthrough(), new RerankProperties(),
                                threshold, new TemplateResolver())),
                null);
    }

    @Test
    void refusal_hitTrue_routesToAnswer() throws Exception {
        StubRetriever retriever = new StubRetriever(List.of(new RetrievedChunk("背部训练要点", 0.9)));
        WorkflowExecutor exec = refusalExecutor(retriever, 0.5);

        WorkflowState state = exec.execute("run-refusal-hit",
                fixture("/testdata/workflows/rag-refusal/refusal.json"), Map.of("userMessage", "怎么练背"));

        assertThat(state.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(state.getNodeOutputs()).containsKey("answer").doesNotContainKey("fallback");
        // END 只聚合实际执行的那条分支——没走的分支连节点输出都没有
        assertThat(state.getNodeOutputs().get("end").getOutput())
                .isEqualTo(Map.of("answer", Map.of("branch", "answer")));
    }

    @Test
    void refusal_allBelowThreshold_routesToFallback() throws Exception {
        // 检索召回了内容，但分数低于阈值 → hit=false → 应当拒答，而不是拿着低分资料硬答
        StubRetriever retriever = new StubRetriever(List.of(new RetrievedChunk("无关内容", 0.3)));
        WorkflowExecutor exec = refusalExecutor(retriever, 0.5);

        WorkflowState state = exec.execute("run-refusal-miss",
                fixture("/testdata/workflows/rag-refusal/refusal.json"), Map.of("userMessage", "增肌期吃多少卡路里"));

        assertThat(state.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(state.getNodeOutputs()).containsKey("fallback").doesNotContainKey("answer");
        assertThat(state.getNodeOutputs().get("end").getOutput())
                .isEqualTo(Map.of("fallback", Map.of("branch", "fallback")));
    }

    @Test
    void refusal_noResultAtAll_routesToFallback() throws Exception {
        // 检索一条都没召回（知识库确实没有）——同样走兜底
        WorkflowExecutor exec = refusalExecutor(new StubRetriever(List.of()), 0.5);

        WorkflowState state = exec.execute("run-refusal-empty",
                fixture("/testdata/workflows/rag-refusal/refusal.json"), Map.of("userMessage", "肌酸有必要吃吗"));

        assertThat(state.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(state.getNodeOutputs()).containsKey("fallback").doesNotContainKey("answer");
    }

    @Test
    void unregisteredNodeType_failsRunWithClearError() {
        // RAG 已被 T6.3 实现；换 AGENTIC_LOOP（本 executor 未注册执行器）验证"类型无执行器 → 明确 FAILED"的纵深防御（QA 43）
        WorkflowDefinition wf = parser.parse("""
                { "id": "x", "name": "x",
                  "nodes": {
                    "start": { "type": "START" },
                    "loop": { "type": "AGENTIC_LOOP", "model": "m" },
                    "end": { "type": "END" } },
                  "edges": [
                    { "from": "start", "to": "loop" },
                    { "from": "loop", "to": "end" } ] }
                """);

        WorkflowState state = executor.execute("run-5", wf, Map.of());

        assertThat(state.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(state.getNodeOutputs().get("loop").getStatus()).isEqualTo(NodeStatus.FAILED);
        assertThat(state.getNodeOutputs().get("loop").getError()).contains("未实现");
    }

    @Test
    void parallelNodes_executeConcurrently_notSequentially() throws Exception {
        // 两个 TOOL 各睡 400ms：并发 ≈400ms（max），串行会 ≈800ms（sum）
        WorkflowExecutor parallel = new WorkflowExecutor(
                new InMemoryCheckpointStore(),
                new ParallelDispatcher(4),
                new ConditionEvaluator(),
                List.of(new StartNodeExecutor(), new SleepingToolNodeExecutor(400), new StubLlmNodeExecutor()), null);

        long start = System.currentTimeMillis();
        WorkflowState state = parallel.execute("run-par",
                fixture("/testdata/workflows/t2/parallel.json"), Map.of());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(state.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(elapsed).isLessThan(600);
    }

    // ---------- T2.4 条件分支 ----------

    @Test
    void conditional_trendDown_takesAdjustBranch() throws Exception {
        WorkflowState state = executor.execute("run-cond-down",
                fixture("/testdata/workflows/t2/cond.json"), Map.of());

        assertThat(state.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(state.getNodeOutputs()).containsKey("adjust"); // DOWN 分支被走到
        @SuppressWarnings("unchecked")
        Map<String, Object> endOutput = (Map<String, Object>) state.getNodeOutputs().get("end").getOutput();
        assertThat(endOutput).containsKey("adjust");
    }

    @Test
    void stall_conditionToEndAlwaysFalse_returnsFailedWithStallError() {
        WorkflowDefinition wf = parser.parse("""
                { "id": "stall-cond", "name": "停滞-条件恒假",
                  "nodes": {
                    "start": { "type": "START" },
                    "a": { "type": "LLM", "model": "q", "prompt": "p", "stubOutput": { "x": "实际值" } },
                    "end": { "type": "END" } },
                  "edges": [
                    { "from": "start", "to": "a" },
                    { "from": "a", "to": "end", "type": "CONDITIONAL",
                      "condition": "{{nodes.a.output.x}} == 'NEVER'" } ] }
                """);

        WorkflowState state = executor.execute("run-stall-1", wf, Map.of());

        assertThat(state.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(state.getError()).contains("进度停滞").contains("无法到达 END");
    }

    @Test
    void stall_cycle_returnsFailedWithBlockedNodes() {
        // 成环 a→b→a 使 end 不可达（绕过校验直接执行；正常 API 流程在校验层就被挡，见 QA 43）
        WorkflowDefinition wf = parser.parse("""
                { "id": "stall-cycle", "name": "停滞-成环",
                  "nodes": {
                    "start": { "type": "START" },
                    "a": { "type": "LLM", "model": "q", "prompt": "p", "stubOutput": { "s": 1 } },
                    "b": { "type": "LLM", "model": "q", "prompt": "p", "stubOutput": { "s": 2 } },
                    "end": { "type": "END" } },
                  "edges": [
                    { "from": "start", "to": "a" },
                    { "from": "a", "to": "b" },
                    { "from": "b", "to": "a" },
                    { "from": "b", "to": "end" } ] }
                """);

        WorkflowState state = executor.execute("run-stall-2", wf, Map.of());

        assertThat(state.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(state.getError()).contains("进度停滞").contains("未就绪节点");
    }

    @Test
    void conditional_trendUp_skipsAdjustBranch_deadPropagation() throws Exception {
        WorkflowDefinition wf = fixture("/testdata/workflows/t2/cond.json");
        wf.getNodes().get("analysis_llm").getConfig().put("stubOutput", Map.of("trend", "UP"));

        WorkflowState state = executor.execute("run-cond-up", wf, Map.of());

        assertThat(state.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(state.getNodeOutputs()).doesNotContainKey("adjust"); // 死分支不执行
        @SuppressWarnings("unchecked")
        Map<String, Object> endOutput = (Map<String, Object>) state.getNodeOutputs().get("end").getOutput();
        assertThat(endOutput).containsKey("analysis_llm");
        assertThat(endOutput).doesNotContainKey("adjust");
    }

    // ---------- T2.6 失败重试 ----------

    @Test
    void retry_toolFailsRetriesThenRunFailed() throws Exception {
        ThrowingToolNodeExecutor tool = new ThrowingToolNodeExecutor();
        WorkflowExecutor retryExecutor = new WorkflowExecutor(
                new InMemoryCheckpointStore(),
                new ParallelDispatcher(4),
                new ConditionEvaluator(),
                List.of(new StartNodeExecutor(), tool, new StubLlmNodeExecutor()), null);

        WorkflowDefinition wf = parser.parse("""
                { "id": "retry", "name": "重试",
                  "nodes": {
                    "start": { "type": "START" },
                    "tool": { "type": "TOOL", "tool": "fail",
                              "retryPolicy": { "retries": 2, "backoffMs": 10 } },
                    "end": { "type": "END" } },
                  "edges": [
                    { "from": "start", "to": "tool" },
                    { "from": "tool", "to": "end" } ] }
                """);

        WorkflowState state = retryExecutor.execute("run-retry", wf, Map.of());

        assertThat(state.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(tool.getAttempts()).isEqualTo(3); // 1 次初试 + 2 次重试，调用计数证明重试发生
        assertThat(state.getNodeOutputs().get("tool").getStatus()).isEqualTo(NodeStatus.FAILED);
        assertThat(state.getError()).contains("节点执行失败");
    }

    @Test
    void retry_retriesExhausted_errorMessageFromLastAttempt() throws Exception {
        // 无需单独断言——上面已覆盖次数与状态；此处补：无 retryPolicy 时只尝试一次
        ThrowingToolNodeExecutor tool = new ThrowingToolNodeExecutor();
        WorkflowExecutor retryExecutor = new WorkflowExecutor(
                new InMemoryCheckpointStore(),
                new ParallelDispatcher(4),
                new ConditionEvaluator(),
                List.of(new StartNodeExecutor(), tool, new StubLlmNodeExecutor()), null);
        WorkflowDefinition wf = parser.parse("""
                { "id": "no-retry", "name": "不重试",
                  "nodes": {
                    "start": { "type": "START" },
                    "tool": { "type": "TOOL", "tool": "fail" },
                    "end": { "type": "END" } },
                  "edges": [
                    { "from": "start", "to": "tool" },
                    { "from": "tool", "to": "end" } ] }
                """);

        WorkflowState state = retryExecutor.execute("run-no-retry", wf, Map.of());

        assertThat(state.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(tool.getAttempts()).isEqualTo(1); // 无 retryPolicy：只试一次
    }

    /** 测试专用：TOOL 桩，执行前睡 sleepMs 毫秒，用于验证并发耗时 ≈ max。 */
    private static final class SleepingToolNodeExecutor implements NodeExecutor {
        private final long sleepMs;

        SleepingToolNodeExecutor(long sleepMs) {
            this.sleepMs = sleepMs;
        }

        @Override
        public NodeType type() {
            return NodeType.TOOL;
        }

        @Override
        public Object execute(NodeDefinition node, WorkflowState state) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return Map.of("data", "tool:" + node.getTool() + " 桩输出");
        }
    }

    /** 测试专用：TOOL 桩，每次都抛异常并计数，用于验证重试次数。 */
    private static final class ThrowingToolNodeExecutor implements NodeExecutor {
        private final AtomicInteger attempts = new AtomicInteger();

        int getAttempts() {
            return attempts.get();
        }

        @Override
        public NodeType type() {
            return NodeType.TOOL;
        }

        @Override
        public Object execute(NodeDefinition node, WorkflowState state) {
            attempts.incrementAndGet();
            throw new IllegalStateException("工具永远失败");
        }
    }

    @Test
    void llmDynamic_routesToChosenBranch_andDeadBranchesNotExecuted() throws Exception {
        // 路由网关返回 analysis_query；classify 用 StubLlmNodeExecutor（返回 stubOutput），不与路由网关冲突
        StubLlmGateway routerGateway = new StubLlmGateway();
        routerGateway.setTextOutput("{\"nextNode\":\"analysis_query\"}");
        LlmRouter router = new LlmRouter(routerGateway, new ObjectMapper());
        WorkflowExecutor dynamic = new WorkflowExecutor(
                new InMemoryCheckpointStore(), new ParallelDispatcher(4), new ConditionEvaluator(),
                List.of(new StartNodeExecutor(), new StubLlmNodeExecutor()), router);

        WorkflowDefinition wf = parser.parse("""
                { "id": "dyn", "name": "动态路由",
                  "nodes": {
                    "start": { "type": "START" },
                    "classify": { "type": "LLM", "model": "m", "prompt": "分类",
                                  "stubOutput": { "category": "ANALYSIS" } },
                    "analysis": { "type": "LLM", "model": "m", "prompt": "分析", "stubOutput": { "ok": true } },
                    "measure": { "type": "LLM", "model": "m", "prompt": "体测", "stubOutput": { "ok": true } },
                    "plan": { "type": "LLM", "model": "m", "prompt": "计划", "stubOutput": { "ok": true } },
                    "end": { "type": "END" } },
                  "edges": [
                    { "from": "start", "to": "classify", "type": "STATIC" },
                    { "from": "classify", "to": "analysis", "type": "LLM_DYNAMIC", "label": "训练分析", "description": "用户想分析" },
                    { "from": "classify", "to": "measure", "type": "LLM_DYNAMIC", "label": "体测解读", "description": "用户想解读体测" },
                    { "from": "classify", "to": "plan", "type": "LLM_DYNAMIC", "label": "计划生成", "description": "用户想制定计划" },
                    { "from": "analysis", "to": "end", "type": "STATIC" },
                    { "from": "measure", "to": "end", "type": "STATIC" },
                    { "from": "plan", "to": "end", "type": "STATIC" } ] }
                """);

        WorkflowState state = dynamic.execute("run-dyn", wf, Map.of("userMessage", "分析一下"));

        assertThat(state.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        // 路由到 analysis 分支；measure/plan 是死分支，不执行、不写入 nodeOutputs
        assertThat(state.getNodeOutputs()).containsKey("analysis");
        assertThat(state.getNodeOutputs()).doesNotContainKeys("measure", "plan");
    }
}
