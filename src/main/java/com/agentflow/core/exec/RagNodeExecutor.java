package com.agentflow.core.exec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentflow.core.dsl.TemplateResolver;
import com.agentflow.core.model.NodeDefinition;
import com.agentflow.core.model.NodeType;
import com.agentflow.core.state.WorkflowState;
import com.agentflow.rag.Retriever;
import com.agentflow.rag.RetrievedChunk;
import org.springframework.stereotype.Component;

/**
 * RAG 节点执行器（T6.3，P6）——检索向量库，产出上下文 chunks 写 state。
 *
 * <p>流程：query 模板解析（{@code {{inputs.x}}} / {@code {{nodes.y.output.z}}}）→
 * 调 {@link Retriever#retrieve}（topK/collection 透传 RAG 节点 config）→ 返回
 * {@code {chunks: [...]}}，对应 DSL 的 {@code {{nodes.x.output.chunks}}} 引用。
 *
 * <p>query 必填由执行器自守（GraphValidator 只校验白名单不校验必填，QA 27——同
 * ToolNodeExecutor 校验 tool）；检索空结果返回空 chunks 不失败，下游 LLM 拿到空上下文。
 * 失败（检索抛错 / 模板缺 key）由 WorkflowExecutor 统一包装为节点 FAILED（T2.6）。
 *
 * <p>并发契约（QA 39）：只读 state、返回结果，不写 state。
 */
@Component
public class RagNodeExecutor implements NodeExecutor {

    private final Retriever retriever;
    private final TemplateResolver templateResolver;

    public RagNodeExecutor(Retriever retriever, TemplateResolver templateResolver) {
        this.retriever = retriever;
        this.templateResolver = templateResolver;
    }

    @Override
    public NodeType type() {
        return NodeType.RAG;
    }

    @Override
    public Object execute(NodeDefinition node, WorkflowState state) {
        String query = node.getQuery();
        if (query == null || query.isBlank()) {
            throw new WorkflowExecutionException("RAG 节点缺少 query 配置：" + node.getId());
        }
        String resolved = templateResolver.resolve(query, templateContext(state));
        List<RetrievedChunk> chunks = retriever.retrieve(resolved, node.getTopK(), node.getCollection());
        return Map.of("chunks", chunks);
    }

    private static Map<String, Object> templateContext(WorkflowState state) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("inputs", state.getInputs());
        ctx.put("nodes", state.getNodeOutputs());
        return ctx;
    }
}
