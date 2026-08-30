package com.agentflow.core.loop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentflow.agent.ChatMessage;
import com.agentflow.agent.ChatResult;
import com.agentflow.agent.LlmGateway;
import com.agentflow.agent.ToolCall;
import com.agentflow.agent.ToolSpec;
import com.agentflow.core.dsl.TemplateResolver;
import com.agentflow.core.exec.NodeExecutor;
import com.agentflow.core.model.NodeDefinition;
import com.agentflow.core.model.NodeType;
import com.agentflow.core.state.WorkflowState;
import com.agentflow.tool.ToolDescriptor;
import com.agentflow.tool.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * AGENTIC_LOOP 节点执行器（T4.3，架构 6.4）——手写 LLM↔Tool 多轮循环。
 *
 * <p>整轮循环 = <b>一次节点执行</b>（与引擎调度解耦：checkpoint 粒度与失败重试都在"整轮"层面，T2.6）。
 * 每轮：
 * <pre>
 * resp = LlmGateway.chatWithTools(systemPrompt, history, tools)
 * 无 toolCalls → 返回文本最终答案
 * 有 toolCalls → 先追加"assistant 工具调用轮"，再逐条调 ToolRegistry.invoke 追加"tool 结果轮"
 * </pre>
 * 直到模型直接给出答案或达到 maxIterations（超限抛错 → 节点 FAILED）。
 *
 * <p>工具定义来自注册中心按 {@code node.tools} 查询（一套 schema 两个用途，QA 49 修订①）；
 * 参数 JSON Schema 校验（T5.3）后续接。outputSchema 暂不处理（QA 50）。
 */
@Component
public class AgenticLoopExecutor implements NodeExecutor {

    private final LlmGateway llmGateway;
    private final ToolRegistry toolRegistry;
    private final TemplateResolver templateResolver;
    private final ObjectMapper mapper;

    public AgenticLoopExecutor(LlmGateway llmGateway, ToolRegistry toolRegistry,
                               TemplateResolver templateResolver, ObjectMapper mapper) {
        this.llmGateway = llmGateway;
        this.toolRegistry = toolRegistry;
        this.templateResolver = templateResolver;
        this.mapper = mapper;
    }

    @Override
    public NodeType type() {
        return NodeType.AGENTIC_LOOP;
    }

    @Override
    public Object execute(NodeDefinition node, WorkflowState state) {
        String systemPrompt = node.getSystemPrompt() == null ? null
                : templateResolver.resolve(node.getSystemPrompt(), templateContext(state));
        List<ToolSpec> tools = resolveTools(node);
        List<ChatMessage> history = new ArrayList<>();
        int maxIterations = node.getMaxIterations();

        for (int i = 0; i < maxIterations; i++) {
            ChatResult result = llmGateway.chatWithTools(systemPrompt, history, tools);
            if (!result.wantsTools()) {
                return result.getText(); // 模型给出最终答案
            }
            // 先追加整轮 assistant 工具调用，再逐条追加 tool 结果（OpenAI 兼容 API 要求 tool_calls 在 tool 结果之前）
            history.add(ChatMessage.assistant(result.getText(), result.getToolCalls()));
            for (ToolCall call : result.getToolCalls()) {
                Object toolResult = invokeTool(call);
                history.add(ChatMessage.tool(call.getId(), call.getName(), toolResult));
            }
        }
        throw new IllegalStateException("达到 maxIterations=" + maxIterations + "，模型仍未给出最终答案");
    }

    private Object invokeTool(ToolCall call) {
        return toolRegistry.invoke(call.getName(), parseArguments(call.getArguments()));
    }

    /**
     * ToolCall.arguments（JSON 字符串）→ Map；空 / null 视为无参。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(arguments, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具参数不是合法 JSON: " + arguments, e);
        }
    }

    /**
     * node.tools → ToolSpec（从注册中心查 ToolDescriptor 组装，QA 49 修订①）。
     */
    private List<ToolSpec> resolveTools(NodeDefinition node) {
        return node.getTools().stream().map(name -> {
            ToolDescriptor d = toolRegistry.get(name);
            if (d == null) {
                throw new IllegalStateException("工具未注册: " + name);
            }
            String schema = d.getParameters() == null ? null : d.getParameters().toString();
            return new ToolSpec(name, d.getDescription(), schema);
        }).toList();
    }

    private static Map<String, Object> templateContext(WorkflowState state) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("inputs", state.getInputs());
        ctx.put("nodes", state.getNodeOutputs());
        return ctx;
    }
}
