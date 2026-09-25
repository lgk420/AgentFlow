package com.agentflow.ability.tool.mcp;

import java.util.List;

import com.agentflow.ability.memory.TestTemplateContext;
import com.agentflow.ability.llm.ChatResult;
import com.agentflow.ability.llm.StubLlmGateway;
import com.agentflow.ability.llm.ToolCall;
import com.agentflow.engine.template.TemplateResolver;
import com.agentflow.engine.node.AgenticLoopExecutor;
import com.agentflow.engine.model.definition.NodeDefinition;
import com.agentflow.engine.model.definition.NodeType;
import com.agentflow.engine.model.state.WorkflowState;
import com.agentflow.ability.tool.ToolRegistry;
import com.agentflow.ability.tool.ToolSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5.5 验收测试——MCP 暴露的工具能被 {@code AGENTIC_LOOP} 调用（chat → mcp tool → 回答案）。
 *
 * <p>连本地 demo MCP server（calc 工具）→ 注册进注册中心 → 手写 loop 脚本化
 * "第一轮要求调 demo_calc → 第二轮给最终答案"，验证模型经注册中心真调远端工具。
 */
class McpAgenticLoopTest {

    @Test
    void agenticLoop_callsMcpTool_andAnswers() {
        try (McpConnector connector = McpConnector.connect(McpConnectorIntegrationTest.demoServerConfig())) {
            ToolRegistry registry = new ToolRegistry(new ToolSchemaValidator(new ObjectMapper()));
            McpToolAdapter adapter = new McpToolAdapter(connector, new ObjectMapper());
            for (McpSchema.Tool tool : connector.listTools()) {
                registry.register(adapter.adapt(tool));
            }

            StubLlmGateway gateway = new StubLlmGateway();
            gateway.setToolDialogues(
                    new ChatResult(null, List.of(new ToolCall("call_1", "demo_calc", "{\"op\":\"mul\",\"a\":6,\"b\":7}"))),
                    new ChatResult("6×7=42", List.of()));

            AgenticLoopExecutor loop = new AgenticLoopExecutor(gateway, registry,
                    new TemplateResolver(), TestTemplateContext.withoutMemory(), new ObjectMapper());

            Object output = loop.execute(loopNode("计算 6×7，用 demo_calc", 5, "demo_calc"),
                    state("goal", "算一下"));

            assertThat(output).isEqualTo("6×7=42");
            assertThat(gateway.getLastTools()).anyMatch(t -> t.getName().equals("demo_calc"));
        }
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
}
