package com.agentflow.core.exec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentflow.core.dsl.TemplateResolver;
import com.agentflow.core.model.NodeDefinition;
import com.agentflow.core.model.NodeType;
import com.agentflow.core.state.NodeOutput;
import com.agentflow.core.state.NodeStatus;
import com.agentflow.core.state.WorkflowState;
import com.agentflow.tool.ToolDescriptor;
import com.agentflow.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T5.4 ToolNodeExecutor 单元测试——真执行器 + typed 参数绑定。
 *
 * <p>覆盖：整段 {@code {{nodes.x.output}} }绑定原对象（Map）、字段引用绑 List、混合文本绑字符串、
 * 裸值原样、默认值生效、注册中心缺工具明确报错、节点缺 tool 配置报错。
 */
class ToolNodeExecutorTest {

    private ToolRegistry registry;
    private ToolNodeExecutor executor;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(null); // 跳过 schema 校验，聚焦绑定
        registry.register(new ToolDescriptor("echo", "回显参数", null, args -> args));
        executor = new ToolNodeExecutor(registry, new TemplateResolver());
    }

    private static NodeDefinition toolNode(String toolName, Map<String, Object> inputs) {
        NodeDefinition node = new NodeDefinition();
        node.setId("t");
        node.setType(NodeType.TOOL);
        node.addConfigField("tool", toolName);
        node.addConfigField("inputs", inputs);
        return node;
    }

    private static WorkflowState state(Map<String, Object> inputs, Map<String, Object> nodeOutputs) {
        WorkflowState s = new WorkflowState();
        s.setInputs(inputs);
        Map<String, NodeOutput> out = new LinkedHashMap<>();
        nodeOutputs.forEach((id, outValue) ->
                out.put(id, new NodeOutput(id, outValue, null, NodeStatus.SUCCEEDED)));
        s.setNodeOutputs(out);
        return s;
    }

    @Test
    void wholeNodeReference_bindsMapObject() {
        Map<String, Object> parseOut = Map.of("date", "20260812", "muscleGroup", "背");
        WorkflowState s = state(Map.of(), Map.of("parse", parseOut));
        Object r = executor.execute(toolNode("echo", Map.of("log", "{{nodes.parse.output}}")), s);
        @SuppressWarnings("unchecked")
        Map<String, Object> bound = (Map<String, Object>) r;
        assertThat(bound.get("log")).isEqualTo(parseOut); // 原对象而非字符串
    }

    @Test
    void fieldReference_bindsList() {
        List<Map<String, Object>> actions = List.of(Map.of("exercise", "哑铃划船"));
        WorkflowState s = state(Map.of(), Map.of("parse", Map.of("actions", actions)));
        Object r = executor.execute(toolNode("echo", Map.of("actions", "{{nodes.parse.output.actions}}")), s);
        @SuppressWarnings("unchecked")
        Map<String, Object> bound = (Map<String, Object>) r;
        assertThat(bound.get("actions")).isEqualTo(actions);
    }

    @Test
    void mixedText_bindsString() {
        WorkflowState s = state(Map.of("day", "20260812"), Map.of());
        Object r = executor.execute(toolNode("echo", Map.of("q", "训练 {{inputs.day}} 记录")), s);
        @SuppressWarnings("unchecked")
        Map<String, Object> bound = (Map<String, Object>) r;
        assertThat(bound.get("q")).isEqualTo("训练 20260812 记录");
    }

    @Test
    void rawValue_passesThroughTyped() {
        WorkflowState s = state(Map.of(), Map.of());
        Object r = executor.execute(toolNode("echo", Map.of("weeks", 6, "flag", true)), s);
        @SuppressWarnings("unchecked")
        Map<String, Object> bound = (Map<String, Object>) r;
        assertThat(bound.get("weeks")).isEqualTo(6);
        assertThat(bound.get("flag")).isEqualTo(true);
    }

    @Test
    void defaultLiteral_appliedWhenMissing_actualWhenPresent() {
        WorkflowState missing = state(Map.of(), Map.of());
        Object r1 = executor.execute(toolNode("echo", Map.of("userId", "{{inputs.userId | 'default-user'}}")), missing);
        @SuppressWarnings("unchecked")
        Map<String, Object> b1 = (Map<String, Object>) r1;
        assertThat(b1.get("userId")).isEqualTo("default-user");

        WorkflowState present = state(Map.of("userId", "alice"), Map.of());
        Object r2 = executor.execute(toolNode("echo", Map.of("userId", "{{inputs.userId}}")), present);
        @SuppressWarnings("unchecked")
        Map<String, Object> b2 = (Map<String, Object>) r2;
        assertThat(b2.get("userId")).isEqualTo("alice");
    }

    @Test
    void missingToolInRegistry_throwsClearError() {
        WorkflowState s = state(Map.of(), Map.of());
        assertThatThrownBy(() -> executor.execute(toolNode("nope", Map.of()), s))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工具不存在");
    }

    @Test
    void missingToolConfig_throws() {
        NodeDefinition node = new NodeDefinition();
        node.setId("bad");
        node.setType(NodeType.TOOL);
        assertThatThrownBy(() -> executor.execute(node, state(Map.of(), Map.of())))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("缺少 tool");
    }
}
