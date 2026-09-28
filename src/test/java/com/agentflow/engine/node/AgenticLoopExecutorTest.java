package com.agentflow.engine.node;

import java.util.List;
import java.util.Map;

import com.agentflow.ability.llm.LlmClient;
import com.agentflow.ability.llm.dto.LlmChatMessage;
import com.agentflow.ability.llm.dto.LlmChatResult;
import com.agentflow.ability.llm.dto.LlmToolCall;
import com.agentflow.ability.llm.dto.LlmToolDefinition;
import com.agentflow.ability.memory.TestTemplateContext;
import com.agentflow.ability.tool.ToolRegistry;
import com.agentflow.ability.tool.dto.ToolDefinition;
import com.agentflow.engine.model.definition.NodeDefinition;
import com.agentflow.engine.model.definition.NodeType;
import com.agentflow.engine.model.definition.OutputSchema;
import com.agentflow.engine.model.definition.WorkflowDefinition;
import com.agentflow.engine.model.state.RunStatus;
import com.agentflow.engine.model.state.WorkflowState;
import com.agentflow.engine.parse.GraphParser;
import com.agentflow.engine.scheduler.ConditionEvaluator;
import com.agentflow.engine.scheduler.ParallelDispatcher;
import com.agentflow.engine.scheduler.WorkflowExecutor;
import com.agentflow.engine.template.TemplateResolver;
import com.agentflow.runtime.checkpoint.InMemoryCheckpointStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.3 AgenticLoopExecutor 验收测试。
 *
 * <p>验收依据（任务拆解 T4.3）：手写 LLM↔Tool 循环——chat → 有 toolCalls 则调注册中心 → 追加 messages →
 * 直到无 toolCalls 或超 maxIterations；断在 maxIterations 有明确报错。
 * mock 掉客户端脚本化多轮，真实模型待环境（Ollama）另行验证。
 *
 * <p><b>「拼 schema 指令 / 解析结构化输出」的实现细节不在这里测</b>——那是 {@code LlmClient} 的职责
 * （{@code SpringAiLlmClientChatStructuredTest}）。这里只验证：声明了 outputSchema 时执行器<b>调了</b>
 * 那两个方法、并把它<b>返回的结果</b>交下去。
 */
class AgenticLoopExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final LlmClient gateway = mock(LlmClient.class);
    private final ToolRegistry registry = new ToolRegistry(new ObjectMapper()); // 本测试不涉及入参校验（T5.3 另有测试）
    private final AgenticLoopExecutor executor =
            new AgenticLoopExecutor(gateway, registry, new TemplateResolver(), TestTemplateContext.withoutMemory(), mapper);

    /**
     * 脚本化多轮回复：第 1 轮吐第 1 个，第 2 轮吐第 2 个……
     */
    private void givenDialogues(LlmChatResult first, LlmChatResult... rest) {
        when(gateway.chatWithTools(any(), any(), any())).thenReturn(first, rest);
    }

    /** 最后一次 chatWithTools 收到的 systemPrompt。 */
    private String lastSystemPrompt() {
        ArgumentCaptor<String> captor = ArgumentCaptor.captor();
        verify(gateway, atLeastOnce()).chatWithTools(captor.capture(), any(), any());
        return captor.getValue();
    }

    /** 最后一次 chatWithTools 收到的 history。 */
    private List<LlmChatMessage> lastHistory() {
        ArgumentCaptor<List<LlmChatMessage>> captor = ArgumentCaptor.captor();
        verify(gateway, atLeastOnce()).chatWithTools(any(), captor.capture(), any());
        return captor.getValue();
    }

    /** 最后一次 chatWithTools 收到的 tools。 */
    private List<LlmToolDefinition> lastTools() {
        ArgumentCaptor<List<LlmToolDefinition>> captor = ArgumentCaptor.captor();
        verify(gateway, atLeastOnce()).chatWithTools(any(), any(), captor.capture());
        return captor.getValue();
    }

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
        registry.register(new ToolDefinition(name, "加法工具", schema, args -> {
            int a = ((Number) args.get("a")).intValue();
            int b = ((Number) args.get("b")).intValue();
            return a + b;
        }));
    }

    @Test
    void loop_callsToolThenReturnsFinalAnswer() {
        registerCalc("calc");
        givenDialogues(
                new LlmChatResult(null, List.of(new LlmToolCall("call_1", "calc", "{\"a\":2,\"b\":3}"))),
                new LlmChatResult("答案是 5", List.of()));

        Object output = executor.execute(loopNode("你是计算器", 5, "calc"), state("goal", "x"));

        assertThat(output).isEqualTo("答案是 5");
        // 第二轮传入的历史 = assistant 工具调用轮 + tool 结果轮（含 id 配对与真实执行结果）
        List<LlmChatMessage> history = lastHistory();
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getRole()).isEqualTo(LlmChatMessage.Role.ASSISTANT);
        assertThat(history.get(0).getLlmToolCalls()).hasSize(1);
        assertThat(history.get(0).getLlmToolCalls().get(0).getId()).isEqualTo("call_1");
        assertThat(history.get(1).getRole()).isEqualTo(LlmChatMessage.Role.TOOL);
        assertThat(history.get(1).getToolCallId()).isEqualTo("call_1");
        assertThat(history.get(1).getToolName()).isEqualTo("calc");
        assertThat(history.get(1).getToolResult()).isEqualTo(5); // 工具真被调用并拿到结果
    }

    @Test
    void loop_directAnswerWithoutTools() {
        registerCalc("calc"); // 工具可用，但模型选择直接回答
        givenDialogues(new LlmChatResult("直接回答", List.of()));

        Object output = executor.execute(loopNode("你是教练", 3, "calc"), state("x", "y"));

        assertThat(output).isEqualTo("直接回答");
        assertThat(lastHistory()).isEmpty(); // 第一轮就出答案，无历史追加
    }

    @Test
    void loop_reachesMaxIterations_throws() {
        registerCalc("calc");
        givenDialogues(
                new LlmChatResult(null, List.of(new LlmToolCall("call_1", "calc", "{\"a\":1,\"b\":1}"))),
                new LlmChatResult(null, List.of(new LlmToolCall("call_2", "calc", "{\"a\":2,\"b\":2}"))));

        assertThatThrownBy(() -> executor.execute(loopNode("你是计算器", 2, "calc"), state("x", "y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxIterations=2");
    }

    @Test
    void loop_nodeToolsUnregistered_throws() {
        givenDialogues(new LlmChatResult("不该走到这", List.of()));

        assertThatThrownBy(() -> executor.execute(loopNode("教练", 3, "ghost"), state("x", "y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工具未注册: ghost");
    }

    @Test
    void loop_modelCallsUnregisteredTool_throws() {
        registerCalc("calc");
        givenDialogues(new LlmChatResult(null, List.of(new LlmToolCall("call_1", "nope", "{}"))));

        assertThatThrownBy(() -> executor.execute(loopNode("教练", 3, "calc"), state("x", "y")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工具不存在: nope");
    }

    @Test
    void tools_resolvedFromRegistry_withSchema() {
        registerCalc("calc");
        givenDialogues(new LlmChatResult("直接回答", List.of()));

        executor.execute(loopNode("你是计算器", 3, "calc"), state("x", "y"));

        List<LlmToolDefinition> tools = lastTools();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).getName()).isEqualTo("calc");
        assertThat(tools.get(0).getInputSchema()).contains("\"number\"");
    }

    @Test
    void systemPrompt_templateResolved() {
        registerCalc("calc");
        givenDialogues(new LlmChatResult("收到", List.of()));

        executor.execute(loopNode("目标：{{inputs.goal}}", 3, "calc"), state("goal", "减脂"));

        assertThat(lastSystemPrompt()).isEqualTo("目标：减脂");
    }

    /** 带 outputSchema 的循环节点（QA 50：声明了它，最终答案要按 JSON 解析成 Map）。 */
    private NodeDefinition loopNodeWithSchema(String systemPrompt, int maxIterations, String schemaJson, String... tools)
            throws Exception {
        NodeDefinition node = loopNode(systemPrompt, maxIterations, tools);
        node.setOutputSchema(new OutputSchema(mapper.readTree(schemaJson)));
        return node;
    }

    @Test
    void outputSchema_appendsInstruction_andParsesFinalAnswer() throws Exception {
        registerCalc("calc");
        givenDialogues(
                new LlmChatResult(null, List.of(new LlmToolCall("call_1", "calc", "{\"a\":2,\"b\":3}"))),
                new LlmChatResult("{\"summary\":\"这次练得不错\",\"report\":\"## 复盘\"}", List.of()));
        // 客户端负责拼指令与解析（细节另有测试），这里只验证执行器把两者调起来、结果交下去
        when(gateway.appendSchemaInstruction(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "\n\n[这里是 schema 指令]");
        when(gateway.parseStructuredOutput(any()))
                .thenReturn(Map.of("summary", "这次练得不错", "report", "## 复盘"));

        Object output = executor.execute(loopNodeWithSchema("你是教练", 5,
                "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"},"
                        + "\"report\":{\"type\":\"string\"}},\"required\":[\"summary\",\"report\"]}",
                "calc"), state("x", "y"));

        // 返回的是解析后的 Map 而不是纯文本——下游模板才能取 {{...output.summary}} / {{...output.report}}
        assertThat(output).isEqualTo(Map.of("summary", "这次练得不错", "report", "## 复盘"));
        // 拼好的 systemPrompt 递给了模型（每一轮都带，这里断言最后一轮收到的）
        assertThat(lastSystemPrompt())
                .contains("你是教练")
                .contains("[这里是 schema 指令]");
    }

    @Test
    void outputSchema_parseFailure_propagates() throws Exception {
        registerCalc("calc");
        givenDialogues(new LlmChatResult("这不是 JSON", List.of()));
        when(gateway.appendSchemaInstruction(any(), any())).thenReturn("你是教练");
        when(gateway.parseStructuredOutput(any()))
                .thenThrow(new IllegalStateException("LLM 结构化输出不是合法 JSON：\n这不是 JSON"));

        assertThatThrownBy(() -> executor.execute(loopNodeWithSchema("你是教练", 3,
                "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}},\"required\":[\"summary\"]}",
                "calc"), state("x", "y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是合法 JSON");
    }

    @Test
    void endToEnd_agenticLoopInWorkflow() throws Exception {
        registerCalc("calc");
        givenDialogues(
                new LlmChatResult(null, List.of(new LlmToolCall("call_1", "calc", "{\"a\":1,\"b\":1}"))),
                new LlmChatResult("容量是 2", List.of()));

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
