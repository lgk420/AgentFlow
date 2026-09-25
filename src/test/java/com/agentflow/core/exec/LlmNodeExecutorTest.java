package com.agentflow.core.exec;

import java.util.List;
import java.util.Map;

import com.agentflow.memory.TestTemplateContext;
import com.agentflow.agent.StubLlmGateway;
import com.agentflow.core.dsl.GraphParser;
import com.agentflow.core.dsl.TemplateResolver;
import com.agentflow.core.model.NodeDefinition;
import com.agentflow.core.model.NodeType;
import com.agentflow.core.model.OutputSchema;
import com.agentflow.core.model.WorkflowDefinition;
import com.agentflow.core.routing.ConditionEvaluator;
import com.agentflow.core.state.NodeOutput;
import com.agentflow.core.state.RunStatus;
import com.agentflow.core.state.WorkflowState;
import com.agentflow.runtime.checkpoint.InMemoryCheckpointStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T4.2 LlmNodeExecutor 验收测试。
 *
 * <p>覆盖：模板解析 prompt；结构化输出（prompt 附 schema + JSON 解析成 Map）；非 JSON 抛错；
 * 端到端跑图（classify 节点输出合法 {category}，END 聚合）。
 */
class LlmNodeExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final StubLlmGateway gateway = new StubLlmGateway();
    private final LlmNodeExecutor executor = new LlmNodeExecutor(gateway, new TemplateResolver(), TestTemplateContext.withoutMemory(), mapper);

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
        gateway.setTextOutput("收到");
        Object output = executor.execute(
                llmNode("用户说：{{inputs.userMessage}}", null), stateWithInput("userMessage", "hi"));

        assertThat(output).isEqualTo("收到");
        assertThat(gateway.getLastUserPrompt()).isEqualTo("用户说：hi"); // 模板已解析
    }

    @Test
    void structuredOutput_promptAppendsSchema_andParsesJson() throws Exception {
        gateway.setTextOutput("{\"category\":\"ANALYSIS\"}");
        Object output = executor.execute(
                llmNode("分类：{{inputs.userMessage}}",
                        "{\"type\":\"object\",\"properties\":{\"category\":{\"type\":\"string\"}},\"required\":[\"category\"]}"),
                stateWithInput("userMessage", "练背"));

        assertThat(output).isEqualTo(Map.of("category", "ANALYSIS"));
        assertThat(gateway.getLastUserPrompt()).contains("分类：练背").contains("category").contains("必须只输出");
    }

    @Test
    void structuredOutput_fencedJson_isTolerated() throws Exception {
        // docs/bugs/01：真实链路模型会把 JSON 包进 markdown 代码块（```json ... ```），解析前剥围栏 + prompt 明确禁止
        gateway.setTextOutput("```json\n{\"category\":\"ANALYSIS\"}\n```");
        Object output = executor.execute(
                llmNode("分类：{{inputs.userMessage}}",
                        "{\"type\":\"object\",\"properties\":{\"category\":{\"type\":\"string\"}},\"required\":[\"category\"]}"),
                stateWithInput("userMessage", "练背"));

        assertThat(output).isEqualTo(Map.of("category", "ANALYSIS"));
        assertThat(gateway.getLastUserPrompt()).contains("不要用 markdown 代码块");
    }

    @Test
    void structuredOutput_invalidJson_throws() throws Exception {
        gateway.setTextOutput("这不是 JSON");
        assertThatThrownBy(() -> executor.execute(llmNode("p", "{\"type\":\"object\"}"), stateWithInput("x", "y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是合法 JSON");
    }

    @Test
    void endToEnd_classifyNode_outputsValidCategory() throws Exception {
        // 验收：健身 DSL 的 classify 能输出合法 {category}（走 Stub 网关，真实模型待环境）
        gateway.setTextOutput("{\"category\":\"ANALYSIS\"}");
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
