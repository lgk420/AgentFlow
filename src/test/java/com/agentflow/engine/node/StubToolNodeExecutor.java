package com.agentflow.engine.node;

import java.util.Map;

import com.agentflow.engine.model.definition.NodeDefinition;
import com.agentflow.engine.model.definition.NodeType;
import com.agentflow.engine.model.state.WorkflowState;

/**
 * TOOL 执行器（桩，T5.4 已被 {@link ToolNodeExecutor} 替换）。
 *
 * <p>返回固定数据 {@code {data: "tool:<工具名> 桩输出"}}，让调度流程先跑通；
 * 不校验参数、不真调工具。保留仅给 P2 时代的单测直接构造用，不再注册为 Bean。
 */
public class StubToolNodeExecutor implements NodeExecutor {

    @Override
    public NodeType type() {
        return NodeType.TOOL;
    }

    @Override
    public Object execute(NodeDefinition node, WorkflowState state) {
        return Map.of("data", "tool:" + node.getTool() + " 桩输出");
    }
}
