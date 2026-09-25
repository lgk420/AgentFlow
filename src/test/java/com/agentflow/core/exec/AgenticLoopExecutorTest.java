package com.agentflow.core.exec;

import java.util.List;
import java.util.Map;

import com.agentflow.memory.TestTemplateContext;
import com.agentflow.agent.ChatMessage;
import com.agentflow.agent.ChatResult;
import com.agentflow.agent.StubLlmGateway;
import com.agentflow.agent.ToolCall;
import com.agentflow.agent.ToolSpec;
import com.agentflow.core.dsl.GraphParser;
import com.agentflow.core.dsl.TemplateResolver;
import com.agentflow.core.loop.AgenticLoopExecutor;
import com.agentflow.core.model.NodeDefinition;
import com.agentflow.core.model.NodeType;
import com.agentflow.core.model.WorkflowDefinition;
import com.agentflow.core.routing.ConditionEvaluator;
import com.agentflow.core.state.RunStatus;
import com.agentflow.core.state.WorkflowState;
import com.agentflow.runtime.checkpoint.InMemoryCheckpointStore;
import com.agentflow.tool.ToolDescriptor;
import com.agentflow.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T4.3 AgenticLoopExecutor 验收测试。
 *
 * <p>验收依据（任务拆解 T4.3）：手写 LLM↔Tool 循环——chat → 有 toolCalls 则调注册中心 → 追加 messages →
 * 直到无 toolCalls 或超 maxIterations；断在 maxIterations 有明确报错。
 * 走 {@link StubLlmGateway} 脚本化多轮，真实模型待环境（Ollama）另行验证。
 */
class AgenticLoopExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final StubLlmGateway gateway = new StubLlmGateway();
    private final ToolRegistry registry = new ToolRegistry(null); // 本测试不涉及入参校验（T5.3 另有测试）
    private final AgenticLoopExecutor executor =
            new AgenticLoopExecutor(gateway, registry, new TemplateResolver(), TestTemplateContext.withoutMemory(), mapper);

    private static NodeDefinition loopNode(String systemPrompt, int maxIterations, String... tools) {
        NodeDefinition node = new NodeDefinition();
        node.setType(NodeType.AGENTIC_LOOP);
        node.getConfig().put("systemPrompt", systemPrompt);
        node.getConfig().put("maxIterations", maxIterations);
        node.getConfig().put("tools", List.of(tools));
        return node;
    }

    private static WorkflowState state(String key, Object value) {
        WorkflowState state = new WorkflowState();
        state.getInputs().put(key, value);
        return state;
    }

    private void registerCalc(String name) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("a").put("type", "number");
        properties.putObject("b").put("type", "number");
        registry.register(new ToolDescriptor(name, "加法工具", schema, args -> {
            int a = ((Number) args.get("a")).intValue();
            int b = ((Number) args.get("b")).intValue();
            return a + b;
        }));
    }

    @Test
    void loop_callsToolThenReturnsFinalAnswer() {
        registerCalc("calc");
        gateway.setToolDialogues(
                new ChatResult(null, List.of(new ToolCall("call_1", "calc", "{\"a\":2,\"b\":3}"))),
                new ChatResult("答案是 5", List.of()));

        Object output = executor.execute(loopNode("你是计算器", 5, "calc"), state("goal", "x"));

        assertThat(output).isEqualTo("答案是 5");
        // 第二轮传入的历史 = assistant 工具调用轮 + tool 结果轮（含 id 配对与真实执行结果）
        List<ChatMessage> history = gateway.getLastHistory();
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getRole()).isEqualTo(ChatMessage.Role.ASSISTANT);
        assertThat(history.get(0).getToolCalls()).hasSize(1);
        assertThat(history.get(0).getToolCalls().get(0).getId()).isEqualTo("call_1");
        assertThat(history.get(1).getRole()).isEqualTo(ChatMessage.Role.TOOL);
        assertThat(history.get(1).getToolCallId()).isEqualTo("call_1");
        assertThat(history.get(1).getToolName()).isEqualTo("calc");
        assertThat(history.get(1).getToolResult()).isEqualTo(5); // 工具真被调用并拿到结果
    }

    @Test
    void loop_directAnswerWithoutTools() {
        registerCalc("calc"); // 工具可用，但模型选择直接回答
        gateway.setToolDialogues(new ChatResult("直接回答", List.of()));

        Object output = executor.execute(loopNode("你是教练", 3, "calc"), state("x", "y"));

        assertThat(output).isEqualTo("直接回答");
        assertThat(gateway.getLastHistory()).isEmpty(); // 第一轮就出答案，无历史追加
    }

    @Test
    void loop_reachesMaxIterations_throws() {
        registerCalc("calc");
        gateway.setToolDialogues(
                new ChatResult(null, List.of(new ToolCall("call_1", "calc", "{\"a\":1,\"b\":1}"))),
                new ChatResult(null, List.of(new ToolCall("call_2", "calc", "{\"a\":2,\"b\":2}"))));

        assertThatThrownBy(() -> executor.execute(loopNode("你是计算器", 2, "calc"), state("x", "y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxIterations=2");
    }

    @Test
    void loop_nodeToolsUnregistered_throws() {
        gateway.setToolDialogues(new ChatResult("不该走到这", List.of()));

        assertThatThrownBy(() -> executor.execute(loopNode("教练", 3, "ghost"), state("x", "y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工具未注册: ghost");
    }

    @Test
    void loop_modelCallsUnregisteredTool_throws() {
        registerCalc("calc");
        gateway.setToolDialogues(new ChatResult(null, List.of(new ToolCall("call_1", "nope", "{}"))));

        assertThatThrownBy(() -> executor.execute(loopNode("教练", 3, "calc"), state("x", "y")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工具不存在: nope");
    }

    @Test
    void tools_resolvedFromRegistry_withSchema() {
        registerCalc("calc");
        gateway.setToolDialogues(new ChatResult("直接回答", List.of()));

        executor.execute(loopNode("你是计算器", 3, "calc"), state("x", "y"));

        List<ToolSpec> tools = gateway.getLastTools();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).getName()).isEqualTo("calc");
        assertThat(tools.get(0).getInputSchema()).contains("\"number\"");
    }

    @Test
    void systemPrompt_templateResolved() {
        registerCalc("calc");
        gateway.setToolDialogues(new ChatResult("收到", List.of()));

        executor.execute(loopNode("目标：{{inputs.goal}}", 3, "calc"), state("goal", "减脂"));

        assertThat(gateway.getLastSystemPrompt()).isEqualTo("目标：减脂");
    }

    @Test
    void endToEnd_agenticLoopInWorkflow() throws Exception {
        registerCalc("calc");
        gateway.setToolDialogues(
                new ChatResult(null, List.of(new ToolCall("call_1", "calc", "{\"a\":1,\"b\":1}"))),
                new ChatResult("容量是 2", List.of()));

        WorkflowExecutor workflowExecutor = new WorkflowExecutor(
                new InMemoryCheckpointStore(),
                new ParallelDispatcher(4),
                new ConditionEvaluator(),
                List.of(new StartNodeExecutor(), executor), null);

        WorkflowDefinition wf = new GraphParser(mapper).parse("""
                { "id": "loop-wf", "name": "loop",
                  "nodes": {
                    "start": { "type": "START" },
                    "agent": { "type": "AGENTIC_LOOP", "model": "m",
                      "systemPrompt": "你是教练", "tools": ["calc"], "maxIterations": 3 },
                    "end": { "type": "END" } },
                  "edges": [
                    { "from": "start", "to": "agent" },
                    { "from": "agent", "to": "end" } ] }
                """);

        WorkflowState state = workflowExecutor.execute("run-loop", wf, Map.of());

        assertThat(state.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(state.getNodeOutputs().get("agent").getOutput()).isEqualTo("容量是 2");
        // END 聚合 agent 输出
        assertThat(state.getNodeOutputs().get("end").getOutput())
                .isEqualTo(Map.of("agent", "容量是 2"));
    }
}
