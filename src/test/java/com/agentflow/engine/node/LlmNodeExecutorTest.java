package com.agentflow.engine.node;

import java.util.List;
import java.util.Map;

import com.agentflow.ability.llm.LlmClient;
import com.agentflow.ability.memory.TestTemplateContext;
import com.agentflow.engine.model.definition.NodeDefinition;
import com.agentflow.engine.model.definition.NodeType;
import com.agentflow.engine.model.definition.OutputSchema;
import com.agentflow.engine.model.definition.WorkflowDefinition;
import com.agentflow.engine.model.state.NodeOutput;
import com.agentflow.engine.model.state.RunStatus;
import com.agentflow.engine.model.state.WorkflowState;
import com.agentflow.engine.parse.GraphParser;
import com.agentflow.engine.scheduler.ConditionEvaluator;
import com.agentflow.engine.scheduler.ParallelDispatcher;
import com.agentflow.engine.scheduler.WorkflowExecutor;
import com.agentflow.engine.template.TemplateResolver;
import com.agentflow.runtime.checkpoint.InMemoryCheckpointStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.2 LlmNodeExecutor 验收测试。
 *
 * <p>覆盖：模板解析 prompt；结构化输出走 {@code chatStructured} 并原样返回 Map；
 * 端到端跑图（classify 节点输出合法 {category}，END 聚合）。
 *
 * <p><b>结构化输出的实现细节不在这里测</b>——「拼 schema 指令 / 剥 markdown 围栏 / 非法 JSON 报错」
 * 是 {@code SpringAiLlmClient} 的职责，测试在 {@code SpringAiLlmClientChatStructuredTest}。
 * 这里 mock 掉客户端，只验证 executor 把模板解析后正确地传下去、并返回结果。
 */
class LlmNodeExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmClient gateway = mock(LlmClient.class);
    private final LlmNodeExecutor executor = new LlmNodeExecutor(gateway, new TemplateResolver(), TestTemplateContext.withoutMemory());

    private NodeDefinition llmNode(String prompt, String schemaJson) throws Exception {
        NodeDefinition node = new NodeDefinition();
        node.setType(NodeType.LLM);
        node.getConfig().put("prompt", prompt);
        if (schemaJson != null) {
            node.setOutputSchema(new OutputSchema(mapper.readTree(schemaJson)));
        }
        return node;
    }

    private static WorkflowState stateWithInput(String key, Object value) {
        WorkflowState state = new WorkflowState();
        state.getInputs().put(key, value);
        return state;
    }

    @Test
    void plainChat_resolvesTemplate() throws Exception {
        when(gateway.chat(any(), any())).thenReturn("收到");

        Object output = executor.execute(
                llmNode("用户说：{{inputs.userMessage}}", null), stateWithInput("userMessage", "hi"));

        assertThat(output).isEqualTo("收到");
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.captor();
        verify(gateway).chat(any(), userPrompt.capture());
        assertThat(userPrompt.getValue()).isEqualTo("用户说：hi"); // 模板已解析
    }

    @Test
    void structuredOutput_resolvesPrompt_andReturnsMap() throws Exception {
        when(gateway.chatStructured(any(), any())).thenReturn(Map.of("category", "ANALYSIS"));

        Object output = executor.execute(
                llmNode("分类：{{inputs.userMessage}}",
                        "{\"type\":\"object\",\"properties\":{\"category\":{\"type\":\"string\"}},\"required\":[\"category\"]}"),
                stateWithInput("userMessage", "练背"));

        assertThat(output).isEqualTo(Map.of("category", "ANALYSIS"));
        ArgumentCaptor<String> prompt = ArgumentCaptor.captor();
        verify(gateway).chatStructured(prompt.capture(), any());
        assertThat(prompt.getValue()).isEqualTo("分类：练背"); // 模板已解析，且只传业务提示词
    }

    @Test
    void endToEnd_classifyNode_outputsValidCategory() throws Exception {
        // 验收：健身 DSL 的 classify 能输出合法 {category}（mock 客户端，真实模型待环境）
        when(gateway.chatStructured(any(), any())).thenReturn(Map.of("category", "ANALYSIS"));
        WorkflowExecutor workflowExecutor = new WorkflowExecutor(
                new InMemoryCheckpointStore(),
                new ParallelDispatcher(4),
                new ConditionEvaluator(),
                List.of(new StartNodeExecutor(), new StubToolNodeExecutor(), executor), null);

        WorkflowDefinition wf = new GraphParser(mapper).parse("""
                { "id": "classify-wf", "name": "classify",
                  "nodes": {
                    "start": { "type": "START" },
                    "classify": { "type": "LLM", "model": "m",
                      "prompt": "你是分流员。用户说：{{inputs.userMessage}}",
                      "outputSchema": { "type": "object",
                        "properties": { "category": { "type": "string", "enum": ["ANALYSIS","MEASURE","PLAN"] } },
                        "required": ["category"] } },
                    "end": { "type": "END" } },
                  "edges": [
                    { "from": "start", "to": "classify" },
                    { "from": "classify", "to": "end" } ] }
                """);

        WorkflowState state = workflowExecutor.execute("run-classify", wf, Map.of("userMessage", "我想练背"));

        assertThat(state.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        NodeOutput classifyOut = state.getNodeOutputs().get("classify");
        assertThat(classifyOut.getOutput()).isEqualTo(Map.of("category", "ANALYSIS"));
        // END 聚合 classify 输出
        assertThat(state.getNodeOutputs().get("end").getOutput())
                .isEqualTo(Map.of("classify", Map.of("category", "ANALYSIS")));
    }
}
