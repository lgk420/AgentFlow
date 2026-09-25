package com.agentflow.engine.node;

import com.agentflow.engine.model.definition.NodeDefinition;
import com.agentflow.engine.model.definition.NodeType;
import com.agentflow.engine.model.state.WorkflowState;
import org.springframework.stereotype.Component;

/**
 * START 执行器：运行入口，无输出（用户输入在 {@code state.inputs}，START 只是放行门槛）。
 */
@Component
public class StartNodeExecutor implements NodeExecutor {

    @Override
    public NodeType type() {
        return NodeType.START;
    }

    @Override
    public Object execute(NodeDefinition node, WorkflowState state) {
        return null;
    }
}
