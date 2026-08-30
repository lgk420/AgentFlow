package com.agentflow.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1.1 领域模型验收测试。
 *
 * <p>覆盖：Jackson 正反序列化往返一致；枚举严格校验非法值；默认值（version=1 / edge.type=STATIC）；
 * 节点平铺字段归一化进 config；outputSchema 原样存储。
 */
class DomainModelTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ---------- 枚举严格校验 ----------

    @Test
    void nodeType_invalidValue_shouldThrow() {
        String json = """
                {
                  "id": "wf",
                  "name": "test",
                  "nodes": { "a": { "type": "FOO" } },
                  "edges": []
                }
                """;
        assertThatThrownBy(() -> mapper.readValue(json, WorkflowDefinition.class))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("FOO");
    }

    @Test
    void edgeType_invalidValue_shouldThrow() {
        String json = """
                { "from": "a", "to": "b", "type": "BAR" }
                """;
        assertThatThrownBy(() -> mapper.readValue(json, EdgeDefinition.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    // ---------- 默认值 ----------

    @Test
    void edgeType_defaultsToStatic() throws Exception {
        String json = """
                { "from": "a", "to": "b" }
                """;
        EdgeDefinition edge = mapper.readValue(json, EdgeDefinition.class);
        assertThat(edge.getType()).isEqualTo(EdgeType.STATIC);
    }

    @Test
    void version_defaultsToOne() throws Exception {
        String json = """
                { "id": "wf", "name": "t", "nodes": {}, "edges": [] }
                """;
        WorkflowDefinition wf = mapper.readValue(json, WorkflowDefinition.class);
        assertThat(wf.getVersion()).isEqualTo(1);
    }

    // ---------- config 归一化 ----------

    @Test
    void node_flatFieldsNormalizeIntoConfig() throws Exception {
        String json = """
                {
                  "type": "LLM",
                  "model": "qwen-plus",
                  "prompt": "hi",
                  "topK": 3
                }
                """;
        NodeDefinition node = mapper.readValue(json, NodeDefinition.class);
        assertThat(node.getType()).isEqualTo(NodeType.LLM);
        assertThat(node.getConfig())
                .containsEntry("model", "qwen-plus")
                .containsEntry("prompt", "hi")
                .containsEntry("topK", 3);
    }

    @Test
    void node_nestedConfigEqualsFlatAndRoundTrips() throws Exception {
        // DSL 3.1 承诺：平铺字段与嵌套 config 对象两种写法等价，存储/导出归一化为嵌套 config
        String flat = """
                { "type": "LLM", "model": "qwen-plus", "prompt": "hi", "topK": 3 }
                """;
        String nested = """
                { "type": "LLM", "config": { "model": "qwen-plus", "prompt": "hi", "topK": 3 } }
                """;
        NodeDefinition flatNode = mapper.readValue(flat, NodeDefinition.class);
        NodeDefinition nestedNode = mapper.readValue(nested, NodeDefinition.class);

        assertThat(nestedNode.getConfig())
                .containsEntry("model", "qwen-plus")
                .containsEntry("prompt", "hi")
                .containsEntry("topK", 3);
        assertThat(nestedNode.getConfig()).isEqualTo(flatNode.getConfig());

        // 导出时 config 以嵌套对象呈现，再导入仍与导出前一致（往返稳定）
        String out = mapper.writeValueAsString(nestedNode);
        assertThat(out).contains("\"config\":{").contains("\"model\":\"qwen-plus\"");
        NodeDefinition back = mapper.readValue(out, NodeDefinition.class);
        assertThat(back.getConfig()).isEqualTo(nestedNode.getConfig());
    }

    // ---------- 类型化访问方法 ----------

    @Test
    void typedAccessors_readFromConfig() throws Exception {
        NodeDefinition loop = mapper.readValue("""
                { "type": "AGENTIC_LOOP",
                  "model": "qwen-plus",
                  "systemPrompt": "你是教练",
                  "tools": ["workout_log_query", "progress_calc"],
                  "maxIterations": 8 }
                """, NodeDefinition.class);
        assertThat(loop.getModel()).isEqualTo("qwen-plus");
        assertThat(loop.getSystemPrompt()).isEqualTo("你是教练");
        assertThat(loop.getTools()).containsExactly("workout_log_query", "progress_calc");
        assertThat(loop.getMaxIterations()).isEqualTo(8);
    }

    @Test
    void typedAccessors_toolAndRag() throws Exception {
        NodeDefinition tool = mapper.readValue("""
                { "type": "TOOL", "tool": "workout_log_query",
                  "inputs": { "muscle_group": "{{inputs.muscleGroup}}" } }
                """, NodeDefinition.class);
        assertThat(tool.getTool()).isEqualTo("workout_log_query");
        assertThat(tool.getInputs()).containsEntry("muscle_group", "{{inputs.muscleGroup}}");
        assertThat(tool.getPrompt()).isNull(); // 其他节点类型的字段不可见

        NodeDefinition rag = mapper.readValue("""
                { "type": "RAG", "query": "动作要点", "topK": 3, "collection": "workout_kb" }
                """, NodeDefinition.class);
        assertThat(rag.getQuery()).isEqualTo("动作要点");
        assertThat(rag.getTopK()).isEqualTo(3);
        assertThat(rag.getCollection()).isEqualTo("workout_kb");
    }

    @Test
    void typedAccessors_defaultsWhenAbsent() throws Exception {
        NodeDefinition rag = mapper.readValue("""
                { "type": "RAG", "query": "q" }
                """, NodeDefinition.class);
        assertThat(rag.getTopK()).isEqualTo(NodeDefinition.DEFAULT_TOP_K);
        assertThat(rag.getCollection()).isNull();

        NodeDefinition loop = mapper.readValue("""
                { "type": "AGENTIC_LOOP", "model": "m" }
                """, NodeDefinition.class);
        assertThat(loop.getMaxIterations()).isEqualTo(NodeDefinition.DEFAULT_MAX_ITERATIONS);
        assertThat(loop.getTools()).isEmpty();
    }

    @Test
    void typedAccessors_typeMismatchThrows() throws Exception {
        NodeDefinition node = mapper.readValue("""
                { "type": "RAG", "topK": "abc" }
                """, NodeDefinition.class);
        assertThatThrownBy(node::getTopK)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");
    }

    @Test
    void typedAccessors_intVal_acceptsNumericStrings() throws Exception {
        NodeDefinition intStr = mapper.readValue("""
                { "type": "RAG", "topK": "5" }
                """, NodeDefinition.class);
        assertThat(intStr.getTopK()).isEqualTo(5);

        NodeDefinition wholeFloat = mapper.readValue("""
                { "type": "RAG", "topK": "3.0" }
                """, NodeDefinition.class);
        assertThat(wholeFloat.getTopK()).isEqualTo(3);

        NodeDefinition loop = mapper.readValue("""
                { "type": "AGENTIC_LOOP", "maxIterations": "10" }
                """, NodeDefinition.class);
        assertThat(loop.getMaxIterations()).isEqualTo(10);
    }

    @Test
    void typedAccessors_intVal_rejectsFractionalString() throws Exception {
        NodeDefinition node = mapper.readValue("""
                { "type": "RAG", "topK": "3.7" }
                """, NodeDefinition.class);
        assertThatThrownBy(node::getTopK)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");
    }

    @Test
    void typedAccessors_ignoredByJackson_roundTripStaysNestedConfig() throws Exception {
        // 访问方法不参与序列化：导出仍是嵌套 config，无顶层重复的 model/prompt（DSL 3.1 承诺）
        NodeDefinition node = mapper.readValue("""
                { "type": "LLM", "model": "qwen-plus", "prompt": "hi" }
                """, NodeDefinition.class);
        String out = mapper.writeValueAsString(node);

        JsonNode tree = mapper.readTree(out);
        assertThat(tree.get("model")).isNull();
        assertThat(tree.get("config").get("model").asText()).isEqualTo("qwen-plus");
        assertThat(tree.get("config").get("prompt").asText()).isEqualTo("hi");

        NodeDefinition back = mapper.readValue(out, NodeDefinition.class);
        assertThat(back.getModel()).isEqualTo("qwen-plus");
        assertThat(back.getPrompt()).isEqualTo("hi");
    }

    // ---------- outputSchema 原样存储 ----------

    @Test
    void outputSchema_storesRawJson() throws Exception {
        String json = """
                {
                  "type": "LLM",
                  "model": "qwen-plus",
                  "prompt": "p",
                  "outputSchema": {
                    "type": "object",
                    "properties": { "category": { "type": "string" } },
                    "required": ["category"]
                  }
                }
                """;
        NodeDefinition node = mapper.readValue(json, NodeDefinition.class);
        assertThat(node.getOutputSchema()).isNotNull();
        JsonNode schema = node.getOutputSchema().getSchema();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("properties").get("category").get("type").asText()).isEqualTo("string");
        assertThat(schema.get("required").get(0).asText()).isEqualTo("category");
    }

    // ---------- 往返一致 ----------

    @Test
    void workflow_roundTrip_preservesAllFields() throws Exception {
        WorkflowDefinition wf = new WorkflowDefinition();
        wf.setId("wf1");
        wf.setName("测试工作流");

        NodeDefinition start = new NodeDefinition();
        start.setId("start");
        start.setType(NodeType.START);

        NodeDefinition classify = new NodeDefinition();
        classify.setId("classify");
        classify.setType(NodeType.LLM);
        classify.getConfig().put("model", "qwen-plus");
        classify.getConfig().put("prompt", "你是教练");

        wf.getNodes().put("start", start);
        wf.getNodes().put("classify", classify);

        EdgeDefinition edge = new EdgeDefinition();
        edge.setFrom("start");
        edge.setTo("classify");
        wf.getEdges().add(edge);

        String json = mapper.writeValueAsString(wf);
        WorkflowDefinition back = mapper.readValue(json, WorkflowDefinition.class);

        assertThat(back.getId()).isEqualTo("wf1");
        assertThat(back.getName()).isEqualTo("测试工作流");
        assertThat(back.getVersion()).isEqualTo(1);
        assertThat(back.getNodes()).containsKeys("start", "classify");
        assertThat(back.getNodes().get("classify").getId()).isEqualTo("classify");
        assertThat(back.getNodes().get("classify").getType()).isEqualTo(NodeType.LLM);
        assertThat(back.getNodes().get("classify").getConfig())
                .containsEntry("model", "qwen-plus")
                .containsEntry("prompt", "你是教练");
        assertThat(back.getEdges()).hasSize(1);
        assertThat(back.getEdges().get(0).getFrom()).isEqualTo("start");
        assertThat(back.getEdges().get(0).getTo()).isEqualTo("classify");
        assertThat(back.getEdges().get(0).getType()).isEqualTo(EdgeType.STATIC);
    }
}
