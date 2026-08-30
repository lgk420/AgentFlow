package com.agentflow.core.exec;

import java.util.LinkedHashMap;
import java.util.Map;

import com.agentflow.agent.LlmGateway;
import com.agentflow.agent.LlmStructuredChat;
import com.agentflow.core.dsl.TemplateResolver;
import com.agentflow.core.model.NodeDefinition;
import com.agentflow.core.model.NodeType;
import com.agentflow.core.model.OutputSchema;
import com.agentflow.core.state.WorkflowState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * LLM 节点执行器（T4.2，P4 真实现）。
 *
 * <p>流程：模板解析 prompt（{@code {{inputs.x}}} / {@code {{nodes.y.output.z}}}）→ 调 {@link LlmGateway}。
 * 有 {@code outputSchema} 走结构化输出（QA 47：prompt 附 schema 指令 + Jackson 解析 JSON → Map，
 * 逻辑在 {@link LlmStructuredChat}，与 LlmRouter 共用），无则返回纯文本。
 * system 传 null（LLM 节点只有 prompt，角色内联其中，见 QA 48）。
 */
@Component
public class LlmNodeExecutor implements NodeExecutor {

    private final LlmGateway llmGateway;
    private final TemplateResolver templateResolver;
    private final ObjectMapper mapper;

    public LlmNodeExecutor(LlmGateway llmGateway, TemplateResolver templateResolver, ObjectMapper mapper) {
        this.llmGateway = llmGateway;
        this.templateResolver = templateResolver;
        this.mapper = mapper;
    }

    @Override
    public NodeType type() {
        return NodeType.LLM;
    }

    @Override
    public Object execute(NodeDefinition node, WorkflowState state) {
        String prompt = templateResolver.resolve(node.getPrompt(), templateContext(state));
        OutputSchema outputSchema = node.getOutputSchema();
        if (outputSchema != null) {
            return LlmStructuredChat.chatStructured(llmGateway, mapper, prompt, outputSchema.getSchema());
        }
        return llmGateway.chat(null, prompt);
    }

    private static Map<String, Object> templateContext(WorkflowState state) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("inputs", state.getInputs());
        ctx.put("nodes", state.getNodeOutputs());
        return ctx;
    }
}
