package com.agentflow.core.routing;

import java.util.Map;

import com.agentflow.core.state.NodeOutput;
import com.agentflow.core.state.NodeStatus;
import com.agentflow.core.state.WorkflowState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2.4 ConditionEvaluator 验收测试。
 *
 * <p>覆盖：{{nodes.x.output.y}} 字段链求值（==/!=）、引用 inputs、多占位符比较。
 */
class ConditionEvaluatorTest {

    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    private static WorkflowState stateWithOutput(String nodeId, Object output) {
        WorkflowState state = new WorkflowState();
        state.getNodeOutputs().put(nodeId, new NodeOutput(nodeId, output, null, NodeStatus.SUCCEEDED));
        return state;
    }

    @Test
    void trendDown_conditionTrue() {
        WorkflowState state = stateWithOutput("analysis_llm", Map.of("trend", "DOWN"));
        assertThat(evaluator.evaluate("{{nodes.analysis_llm.output.trend}} == 'DOWN'", state)).isTrue();
    }

    @Test
    void trendUp_conditionFalse() {
        WorkflowState state = stateWithOutput("analysis_llm", Map.of("trend", "UP"));
        assertThat(evaluator.evaluate("{{nodes.analysis_llm.output.trend}} == 'DOWN'", state)).isFalse();
        assertThat(evaluator.evaluate("{{nodes.analysis_llm.output.trend}} != 'DOWN'", state)).isTrue();
    }

    @Test
    void inputsReference() {
        WorkflowState state = new WorkflowState();
        state.getInputs().put("flag", true);
        assertThat(evaluator.evaluate("{{inputs.flag}} == true", state)).isTrue();
    }

    @Test
    void multiplePlaceholders() {
        WorkflowState state = new WorkflowState();
        state.getNodeOutputs().put("a", new NodeOutput("a", Map.of("x", "1"), null, NodeStatus.SUCCEEDED));
        state.getNodeOutputs().put("b", new NodeOutput("b", Map.of("y", "1"), null, NodeStatus.SUCCEEDED));
        assertThat(evaluator.evaluate("{{nodes.a.output.x}} == {{nodes.b.output.y}}", state)).isTrue();
    }
}
