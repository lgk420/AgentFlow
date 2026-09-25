package com.agentflow.engine.node;

import java.util.LinkedHashMap;
import java.util.Map;

import com.agentflow.engine.template.TemplateContextFactory;
import com.agentflow.engine.template.TemplateResolver;
import com.agentflow.engine.model.definition.NodeDefinition;
import com.agentflow.engine.model.definition.NodeType;
import com.agentflow.engine.model.state.WorkflowState;
import com.agentflow.ability.tool.ToolRegistry;
import org.springframework.stereotype.Component;

/**
 * TOOL 节点执行器（T5.4，替换 StubToolNodeExecutor）——调注册中心里的真工具。
 *
 * <p>流程：把 {@code inputs} 各参数按模板类型化绑定（{@link TemplateResolver#resolveObject}：
 * 整段 {@code {{nodes.x.output}} }取原对象、混合文本转字符串、裸值原样）→
 * 交 {@link ToolRegistry#invoke}（内含 T5.3 入参 schema 校验）→ 返回工具结果写 state。
 *
 * <p>并发契约（QA 39）：只读 state、返回结果，不写 state——写由调度器批后统一执行。
 * 工具失败/校验失败抛异常，由 {@link WorkflowExecutor} 按 retryPolicy 重试后判 FAILED（T2.6）。
 */
@Component
public class ToolNodeExecutor implements NodeExecutor {

    private final ToolRegistry toolRegistry;
    private final TemplateResolver templateResolver;
    private final TemplateContextFactory templateContextFactory;

    public ToolNodeExecutor(ToolRegistry toolRegistry, TemplateResolver templateResolver,
                            TemplateContextFactory templateContextFactory) {
        this.toolRegistry = toolRegistry;
        this.templateResolver = templateResolver;
        this.templateContextFactory = templateContextFactory;
    }

    @Override
    public NodeType type() {
        return NodeType.TOOL;
    }

    @Override
    public Object execute(NodeDefinition node, WorkflowState state) {
        String toolName = node.getTool();
        if (toolName == null || toolName.isBlank()) {
            throw new WorkflowExecutionException("TOOL 节点缺少 tool 配置：" + node.getId());
        }
        Map<String, Object> bound = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : node.getInputs().entrySet()) {
            bound.put(e.getKey(), templateResolver.resolveObject(e.getValue(), templateContextFactory.contextFor(state)));
        }
        return toolRegistry.invoke(toolName, bound);
    }
}
