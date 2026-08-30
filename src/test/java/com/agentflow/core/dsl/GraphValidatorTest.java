package com.agentflow.core.dsl;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.agentflow.core.model.WorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1.3 校验器验收测试。
 *
 * <p>覆盖：健身 DSL 好图通过；testdata 下 12 个坏图 fixture 各触发明确错误；聚合报错（一个坏图同时报多条）；
 * id 字符集 / START 带 config 等内联边界用例。
 */
class GraphValidatorTest {

    private final GraphParser parser = new GraphParser(new ObjectMapper());
    private final GraphValidator validator = new GraphValidator();

    private WorkflowDefinition parse(String resource) throws Exception {
        String json = new String(
                getClass().getResourceAsStream(resource).readAllBytes(), StandardCharsets.UTF_8);
        return parser.parse(json);
    }

    // ---------- 好图：附录健身 DSL ----------

    @Test
    void fitnessCoachGraph_isValid() throws Exception {
        List<String> errors = validator.validate(parse("/testdata/workflows/legacy-fitness-coach/fitness-coach.json"));
        assertThat(errors).isEmpty();
    }

    // ---------- 坏图 fixture：每个触发对应错误 ----------

    @Test
    void cycle_flagged() throws Exception {
        List<String> errors = validator.validate(parse("/testdata/workflows/validation/cycle.json"));
        assertThat(errors).anyMatch(e -> e.contains("图存在环")
                && isForwardCycle(e, List.of("start->a", "a->b", "b->a", "b->end")));
    }

    @Test
    void threeNodeCycle_flagged_withForwardDirection() {
        // a→b→c→a：3 节点环能暴露"前驱回溯路径需反转"的边界（若不反转会误报 a→c 这种不存在的边）
        WorkflowDefinition wf = parser.parse("""
                { "id": "wf", "name": "t",
                  "nodes": {
                    "start": { "type": "START" },
                    "a": { "type": "LLM", "model": "m", "prompt": "p" },
                    "b": { "type": "LLM", "model": "m", "prompt": "p" },
                    "c": { "type": "LLM", "model": "m", "prompt": "p" },
                    "end": { "type": "END" } },
                  "edges": [
                    { "from": "start", "to": "a" },
                    { "from": "a", "to": "b" },
                    { "from": "b", "to": "c" },
                    { "from": "c", "to": "a" },
                    { "from": "a", "to": "end" } ] }
                """);
        List<String> errors = validator.validate(wf);
        assertThat(errors).anyMatch(e -> e.contains("图存在环")
                && isForwardCycle(e, List.of("start->a", "a->b", "b->c", "c->a", "a->end")));
    }

    /**
     * 校验环路径串的每个相邻对都是正向边（方向正确性验证），且至少为 [x, y, x] 闭环。
     */
    private static boolean isForwardCycle(String message, List<String> forwardEdges) {
        String cycle = message.substring(message.indexOf('：') + 1);
        String[] nodes = cycle.split(" → ");
        if (nodes.length < 3) {
            return false;
        }
        for (int i = 0; i + 1 < nodes.length; i++) {
            if (!forwardEdges.contains(nodes[i] + "->" + nodes[i + 1])) {
                return false;
            }
        }
        return true;
    }

    @Test
    void missingNodeRef_flagged() throws Exception {
        List<String> errors = validator.validate(parse("/testdata/workflows/validation/edge-ref.json"));
        assertThat(errors).anyMatch(e -> e.contains("引用了不存在的节点 missing"));
    }

    @Test
    void nodeConfigNotInWhitelist_flagged() throws Exception {
        List<String> errors = validator.validate(parse("/testdata/workflows/validation/node-config.json"));
        assertThat(errors).anyMatch(e -> e.contains("LLM 节点不支持字段 tool"));
    }

    @Test
    void outputSchemaOnTool_flagged() throws Exception {
        List<String> errors = validator.validate(parse("/testdata/workflows/validation/outputschema.json"));
        assertThat(errors).anyMatch(e -> e.contains("TOOL 节点不支持字段 outputSchema"));
    }

    @Test
    void conditionalMissingCondition_flagged() throws Exception {
        List<String> errors = validator.validate(parse("/testdata/workflows/validation/cond-missing.json"));
        assertThat(errors).anyMatch(e -> e.contains("CONDITIONAL 边缺少 condition"));
    }

    @Test
    void malformedCondition_flagged() throws Exception {
        List<String> errors = validator.validate(parse("/testdata/workflows/validation/cond-malformed.json"));
        assertThat(errors).anyMatch(e -> e.contains("condition 结构非法"));
    }

    @Test
    void staticEdgeWithCondition_flagged() throws Exception {
        List<String> errors = validator.validate(parse("/testdata/workflows/validation/edge-config.json"));
        assertThat(errors).anyMatch(e -> e.contains("STATIC 边不支持字段 condition"));
    }

    @Test
    void llmDynamicMissingLabel_flagged() throws Exception {
        List<String> errors = validator.validate(parse("/testdata/workflows/validation/llm-dyn-label.json"));
        assertThat(errors).anyMatch(e -> e.contains("LLM_DYNAMIC 边缺少 label"));
    }

    @Test
    void twoStart_flagged() throws Exception {
        List<String> errors = validator.validate(parse("/testdata/workflows/validation/two-start.json"));
        assertThat(errors).anyMatch(e -> e.contains("必须且仅有一个 START"));
    }

    @Test
    void noEnd_flagged() throws Exception {
        List<String> errors = validator.validate(parse("/testdata/workflows/validation/no-end.json"));
        assertThat(errors).anyMatch(e -> e.contains("必须且仅有一个 END"));
    }

    @Test
    void edgeToStart_flagged() throws Exception {
        List<String> errors = validator.validate(parse("/testdata/workflows/validation/edge-to-start.json"));
        assertThat(errors).anyMatch(e -> e.contains("START 不能有入边"));
    }

    @Test
    void edgeFromEnd_flagged() throws Exception {
        List<String> errors = validator.validate(parse("/testdata/workflows/validation/edge-from-end.json"));
        assertThat(errors).anyMatch(e -> e.contains("END 不能有出边"));
    }

    // ---------- 内联边界用例 ----------

    @Test
    void invalidWorkflowId_flagged() {
        WorkflowDefinition wf = parser.parse("""
                { "id": "bad id", "name": "t",
                  "nodes": { "start": { "type": "START" }, "end": { "type": "END" } },
                  "edges": [ { "from": "start", "to": "end" } ] }
                """);
        List<String> errors = validator.validate(wf);
        assertThat(errors).anyMatch(e -> e.contains("工作流 id 非法"));
    }

    @Test
    void invalidNodeId_flagged() {
        WorkflowDefinition wf = parser.parse("""
                { "id": "wf", "name": "t",
                  "nodes": { "start node": { "type": "START" }, "end": { "type": "END" } },
                  "edges": [ { "from": "start node", "to": "end" } ] }
                """);
        List<String> errors = validator.validate(wf);
        assertThat(errors).anyMatch(e -> e.contains("节点 id 非法"));
    }

    @Test
    void startNodeWithConfig_flagged() {
        WorkflowDefinition wf = parser.parse("""
                { "id": "wf", "name": "t",
                  "nodes": { "start": { "type": "START", "model": "x" }, "end": { "type": "END" } },
                  "edges": [ { "from": "start", "to": "end" } ] }
                """);
        List<String> errors = validator.validate(wf);
        assertThat(errors).anyMatch(e -> e.contains("START 节点不支持字段 model"));
    }

    @Test
    void multipleErrors_collectedTogether() {
        // 同一个坏图同时缺 END、引用不存在节点 → 聚合返回两条错误
        WorkflowDefinition wf = parser.parse("""
                { "id": "wf", "name": "t",
                  "nodes": { "start": { "type": "START" }, "a": { "type": "LLM", "model": "m", "prompt": "p" } },
                  "edges": [ { "from": "start", "to": "a" }, { "from": "a", "to": "missing" } ] }
                """);
        List<String> errors = validator.validate(wf);
        assertThat(errors).anyMatch(e -> e.contains("必须且仅有一个 END"));
        assertThat(errors).anyMatch(e -> e.contains("引用了不存在的节点 missing"));
    }
}
