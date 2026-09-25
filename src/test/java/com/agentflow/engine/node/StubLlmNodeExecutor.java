package com.agentflow.engine.node;

import com.agentflow.engine.node.LlmNodeExecutor;

import com.agentflow.engine.node.NodeExecutor;

import java.util.Map;

import com.agentflow.engine.model.definition.NodeDefinition;
import com.agentflow.engine.model.definition.NodeType;
import com.agentflow.engine.model.state.WorkflowState;

/**
 * LLM 执行器桩（测试替身，QA 36）——真实现 {@link LlmNodeExecutor} 上后从主代码挪到测试。
 *
 * <p>读 config 的 {@code stubOutput} 返回假输出（让 T2.2~T2.6 测试能控制 LLM 结果），
 * 未配置回退 {@code {stub:true}}。<b>非 bean</b>（生产只注入 {@link LlmNodeExecutor}）。
 */
public class StubLlmNodeExecutor implements NodeExecutor {

    @Override
    public NodeType type() {
        return NodeType.LLM;
    }

    @Override
    public Object execute(NodeDefinition node, WorkflowState state) {
        Object stub = node.getConfig().get("stubOutput");
        return stub != null ? stub : Map.of("stub", true);
    }
}
