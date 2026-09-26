package com.agentflow.engine.node;

import com.agentflow.ability.llm.LlmClient;
import com.agentflow.engine.template.TemplateContextFactory;
import com.agentflow.engine.template.TemplateResolver;
import com.agentflow.engine.model.definition.NodeDefinition;
import com.agentflow.engine.model.definition.NodeType;
import com.agentflow.engine.model.definition.OutputSchema;
import com.agentflow.engine.model.state.WorkflowState;
import org.springframework.stereotype.Component;

/**
 * LLM 节点执行器（T4.2，P4 真实现）。
 *
 * <p>流程：模板解析 prompt（{@code {{inputs.x}}} / {@code {{nodes.y.output.z}}} / {@code {{memory.*}}}）
 * → 调 {@link LlmClient}。
 * 有 {@code outputSchema} 走结构化输出（QA 47：prompt 附 schema 指令 + Jackson 解析 JSON → Map，
 * 逻辑在 {@link LlmStructuredChat}，与 LlmRouter 共用），无则返回纯文本。
 * system 传 null（LLM 节点只有 prompt，角色内联其中，见 QA 48）。
 *
 * <p>T10.2：模板上下文改由 {@link TemplateContextFactory} 统一拼装（原先每个执行器各有一份
 * 重复的 templateContext()，加 memory 命名空间后会变成四份）。
 */
@Component
public class LlmNodeExecutor implements NodeExecutor {

    private final LlmClient llmClient;
    private final TemplateResolver templateResolver;
    private final TemplateContextFactory templateContextFactory;

    public LlmNodeExecutor(LlmClient llmClient, TemplateResolver templateResolver,
                           TemplateContextFactory templateContextFactory) {
        this.llmClient = llmClient;
        this.templateResolver = templateResolver;
        this.templateContextFactory = templateContextFactory;
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
            return llmClient.chatStructured(prompt, outputSchema.getSchema());
        }
        return llmClient.chat(null, prompt);
    }
}
