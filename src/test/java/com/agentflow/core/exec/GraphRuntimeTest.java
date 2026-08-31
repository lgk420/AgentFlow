package com.agentflow.core.exec;

import java.util.List;
import java.util.Map;

import com.agentflow.core.dsl.GraphParser;
import com.agentflow.core.model.WorkflowDefinition;
import com.agentflow.core.routing.ConditionEvaluator;
import com.agentflow.core.state.NodeOutput;
import com.agentflow.core.state.NodeStatus;
import com.agentflow.core.state.WorkflowState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7.3 调度抽取 · GraphRuntime 测试（QA 79：边解析结果持久化 + 重算就绪）。
 *
 * <p>覆盖：STATIC 链就绪；CONDITIONAL 一真一假（真就绪/假死分支）；死分支级联到 END。
 */
class GraphRuntimeTest {

    private final GraphParser parser = new GraphParser(new ObjectMapper());
    private final GraphRuntime runtime = new GraphRuntime(new ConditionEvaluator(), null);

    private WorkflowDefinition parse(String json) {
        return parser.parse(json);
    }

    private static WorkflowState stateWith(String nodeId, Object output) {
        WorkflowState state = new WorkflowState();
        state.getNodeOutputs().put(nodeId, new NodeOutput(nodeId, output, null, NodeStatus.SUCCEEDED));
        return state;
    }

    @Test
    void staticChain_resolvesStartToEnd() {
        WorkflowDefinition wf = parse("""
                { "id":"chain","name":"chain",
                  "nodes":{ "start":{"type":"START"}, "a":{"type":"TOOL","tool":"x"}, "end":{"type":"END"} },
                  "edges":[
                    {"from":"start","to":"a","type":"STATIC"},
                    {"from":"a","to":"end","type":"STATIC"}] }""");

        WorkflowState state = stateWith("start", Map.of());
        assertThat(runtime.resolveOutEdges("start", wf, state, false)).containsExactly("a");

        state.getNodeOutputs().put("a", new NodeOutput("a", Map.of("v", 1), null, NodeStatus.SUCCEEDED));
        assertThat(runtime.resolveOutEdges("a", wf, state, false)).containsExactly("end");
        assertThat(state.getNodeOutputs().containsKey("end")).isFalse(); // END 由 worker 执行
    }

    @Test
    void conditionBranch_firesOneDeadOther() {
        WorkflowDefinition wf = parse("""
                { "id":"cond","name":"cond",
                  "nodes":{ "start":{"type":"START"}, "a":{"type":"TOOL","tool":"x"},
                            "b":{"type":"TOOL","tool":"y"}, "c":{"type":"TOOL","tool":"z"}, "end":{"type":"END"} },
                  "edges":[
                    {"from":"start","to":"a","type":"STATIC"},
                    {"from":"a","to":"b","type":"CONDITIONAL","condition":"{{nodes.a.output.x}} == 'go'"},
                    {"from":"a","to":"c","type":"CONDITIONAL","condition":"{{nodes.a.output.x}} == 'stop'"},
                    {"from":"b","to":"end","type":"STATIC"},
                    {"from":"c","to":"end","type":"STATIC"}] }""");

        WorkflowState state = stateWith("a", Map.of("x", "go"));
        List<String> ready = runtime.resolveOutEdges("a", wf, state, false);

        assertThat(ready).containsExactly("b");      // 条件真的 b 就绪
        assertThat(state.getDeadNodes()).contains("c"); // 条件假的 c 死分支
        assertThat(state.getNodeOutputs().containsKey("b")).isFalse();

        // b 完成后，end 就绪（b→end fired，c→end 死边已解析）
        state.getNodeOutputs().put("b", new NodeOutput("b", Map.of(), null, NodeStatus.SUCCEEDED));
        assertThat(runtime.resolveOutEdges("b", wf, state, false)).containsExactly("end");
    }

    @Test
    void deadBranch_cascadesToEnd() {
        WorkflowDefinition wf = parse("""
                { "id":"dead","name":"dead",
                  "nodes":{ "a":{"type":"TOOL","tool":"x"}, "b":{"type":"TOOL","tool":"y"},
                            "c":{"type":"TOOL","tool":"z"}, "end":{"type":"END"} },
                  "edges":[
                    {"from":"a","to":"b","type":"CONDITIONAL","condition":"{{nodes.a.output.x}} == 'never'"},
                    {"from":"b","to":"c","type":"STATIC"},
                    {"from":"c","to":"end","type":"STATIC"}] }""");

        WorkflowState state = stateWith("a", Map.of("x", "go"));
        assertThat(runtime.resolveOutEdges("a", wf, state, false)).isEmpty();

        // b 死 → 级联 c 死 → 级联 end 死（都不执行）
        assertThat(state.getDeadNodes()).contains("b", "c", "end");
        assertThat(state.getNodeOutputs()).doesNotContainKeys("b", "c", "end");
    }
}
