package com.agentflow.core.exec;

import com.agentflow.agent.LlmGateway;
import com.agentflow.agent.LlmStructuredChat;
import com.agentflow.core.dsl.TemplateContextFactory;
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
 * <p>流程：模板解析 prompt（{@code {{inputs.x}}} / {@code {{nodes.y.output.z}}} / {@code {{memory.*}}}）
 * → 调 {@link LlmGateway}。
 * 有 {@code outputSchema} 走结构化输出（QA 47：prompt 附 schema 指令 + Jackson 解析 JSON → Map，
 * 逻辑在 {@link LlmStructuredChat}，与 LlmRouter 共用），无则返回纯文本。
 * system 传 null（LLM 节点只有 prompt，角色内联其中，见 QA 48）。
 *
 * <p>T10.2：模板上下文改由 {@link TemplateContextFactory} 统一拼装（原先每个执行器各有一份
 * 重复的 templateContext()，加 memory 命名空间后会变成四份）。
 */
@Component
public class LlmNodeExecutor implements NodeExecutor {

    private final LlmGateway llmGateway;
    private final TemplateResolver templateResolver;
    private final TemplateContextFactory templateContextFactory;
    private final ObjectMapper mapper;

    public LlmNodeExecutor(LlmGateway llmGateway, TemplateResolver templateResolver,
                           TemplateContextFactory templateContextFactory, ObjectMapper mapper) {
        this.llmGateway = llmGateway;
        this.templateResolver = templateResolver;
        this.templateContextFactory = templateContextFactory;
        this.mapper = mapper;
    }

    @Override
    public NodeType type() {
        return NodeType.LLM;
    }

    @Override
    public Object execute(NodeDefinition node, WorkflowState state) {
        String prompt = templateResolver.resolve(node.getPrompt(), templateContextFactory.contextFor(state));
        OutputSchema outputSchema = node.getOutputSchema();
        if (outputSchema != null) {
            return LlmStructuredChat.chatStructured(llmGateway, mapper, prompt, outputSchema.getSchema());
        }
        return llmGateway.chat(null, prompt);
    }
}
