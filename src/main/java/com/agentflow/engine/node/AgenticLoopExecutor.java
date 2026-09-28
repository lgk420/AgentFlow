package com.agentflow.engine.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.agentflow.ability.llm.dto.LlmChatMessage;
import com.agentflow.ability.llm.dto.LlmChatResult;
import com.agentflow.ability.llm.LlmClient;
import com.agentflow.ability.llm.dto.LlmToolCall;
import com.agentflow.ability.llm.dto.LlmToolDefinition;
import com.agentflow.engine.template.TemplateContextFactory;
import com.agentflow.engine.template.TemplateResolver;
import com.agentflow.engine.model.definition.NodeDefinition;
import com.agentflow.engine.model.definition.NodeType;
import com.agentflow.engine.model.definition.OutputSchema;
import com.agentflow.engine.model.state.WorkflowState;
import com.agentflow.ability.tool.dto.ToolDefinition;
import com.agentflow.ability.tool.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * AGENTIC_LOOP 节点执行器（T4.3，架构 6.4）——手写 LLM↔Tool 多轮循环。
 *
 * <p>整轮循环 = <b>一次节点执行</b>（与引擎调度解耦：checkpoint 粒度与失败重试都在"整轮"层面，T2.6）。
 * 每轮：
 * <pre>
 * resp = LlmClient.chatWithTools(systemPrompt, history, tools)
 * 无 toolCalls → 返回文本最终答案
 * 有 toolCalls → 先追加"assistant 工具调用轮"，再逐条调 ToolRegistry.invoke 追加"tool 结果轮"
 * </pre>
 * 直到模型直接给出答案或达到 maxIterations（超限抛错 → 节点 FAILED）。
 *
 * <p>工具定义来自注册中心按 {@code node.tools} 查询（一套 schema 两个用途，QA 49 修订①）；
 * 入参 JSON Schema 校验由 {@link ToolRegistry#invoke} 负责（T5.3）。
 *
 * <p><b>outputSchema（QA 50 里"延后"的那件事，现已接上）</b>：声明了它 →
 * ① schema 指令拼到 systemPrompt 末尾；② 最终答案按 JSON 解析成 Map 返回（而不是纯文本）。
 * <b>不是"循环完再调一次 LLM 转格式"</b>——那多一次调用；循环的最后一轮本来就是模型的最终作答，
 * 让它直接按 JSON 答即可。没声明 outputSchema 则原样返回文本（行为与之前完全一致）。
 */
@Component
public class AgenticLoopExecutor implements NodeExecutor {

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final TemplateResolver templateResolver;
    private final TemplateContextFactory templateContextFactory;
    private final ObjectMapper mapper;

    public AgenticLoopExecutor(LlmClient llmClient, ToolRegistry toolRegistry,
                               TemplateResolver templateResolver, TemplateContextFactory templateContextFactory,
                               ObjectMapper mapper) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.templateResolver = templateResolver;
        this.templateContextFactory = templateContextFactory;
        this.mapper = mapper;
    }

    @Override
    public NodeType type() {
        return NodeType.AGENTIC_LOOP;
    }

    @Override
    public Object execute(NodeDefinition node, WorkflowState state) {
        String systemPrompt = node.getSystemPrompt() == null ? null
                : templateResolver.resolve(node.getSystemPrompt(), templateContextFactory.contextFor(state));
        OutputSchema outputSchema = node.getOutputSchema();
        if (outputSchema != null) {
            // schema 指令拼在 systemPrompt 末尾：下面每一轮都会带上它，最后一轮模型才会按 JSON 作答
            systemPrompt = llmClient.appendSchemaInstruction(systemPrompt, outputSchema.getSchema());
        }
        List<LlmToolDefinition> tools = resolveTools(node);
        List<LlmChatMessage> history = new ArrayList<>();
        int maxIterations = node.getMaxIterations();

        for (int i = 0; i < maxIterations; i++) {
            LlmChatResult result = llmClient.chatWithTools(systemPrompt, history, tools);
            if (!result.wantsTools()) {
                // 模型给出最终答案；声明了 outputSchema 就解析成 Map，否则原样返回文本
                return outputSchema == null
                        ? result.getText()
                        : llmClient.parseStructuredOutput(result.getText());
            }
            // 先追加整轮 assistant 工具调用，再逐条追加 tool 结果（OpenAI 兼容 API 要求 tool_calls 在 tool 结果之前）
            history.add(LlmChatMessage.assistant(result.getText(), result.getToolCalls()));
            for (LlmToolCall call : result.getToolCalls()) {
                Object toolResult = invokeTool(call);
                history.add(LlmChatMessage.tool(call.getId(), call.getName(), toolResult));
            }
        }
        throw new IllegalStateException("达到 maxIterations=" + maxIterations + "，模型仍未给出最终答案");
    }

    private Object invokeTool(LlmToolCall call) {
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
     * node.tools → LlmToolDefinition（从注册中心查 ToolDescriptor 组装，QA 49 修订①）。
     */
    private List<LlmToolDefinition> resolveTools(NodeDefinition node) {
        return node.getTools().stream().map(name -> {
            ToolDefinition d = toolRegistry.get(name);
            if (d == null) {
                throw new IllegalStateException("工具未注册: " + name);
            }
            String schema = d.getParameters() == null ? null : d.getParameters().toString();
            return new LlmToolDefinition(name, d.getDescription(), schema);
        }).toList();
    }
}
