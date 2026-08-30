package com.agentflow.core.dsl;

import java.nio.charset.StandardCharsets;

import com.agentflow.core.model.EdgeDefinition;
import com.agentflow.core.model.EdgeType;
import com.agentflow.core.model.NodeDefinition;
import com.agentflow.core.model.NodeType;
import com.agentflow.core.model.WorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1.2 解析器验收测试。
 *
 * <p>覆盖：健身教练助手 DSL（fitness-coach）完整解析（节点/边数量、节点 id 从键填充、outputSchema、3 种节点类型全覆盖）；
 * 错误分支（坏 JSON / 空 JSON / 非法枚举 / 未知字段）；内层 id 以键为准覆盖。
 * 旧版三分支 DSL（含 CONDITIONAL / LLM_DYNAMIC 边的 legacy-intent-router）保留在 legacy-intent-router/ 供回归。
 *
 * <p>注：测试用 {@code new ObjectMapper()}（默认严格拒绝未知字段），生产已在 application.yml 配
 * {@code fail-on-unknown-properties: true}，两者行为一致。
 */
class GraphParserTest {

    private final GraphParser parser = new GraphParser(new ObjectMapper());

    // ---------- 验收：健身教练助手 DSL（每日日志管线）解析成完整对象 ----------

    @Test
    void parse_fitnessCoachJson_fullGraph() throws Exception {
        String json = new String(
                getClass().getResourceAsStream("/testdata/workflows/legacy-fitness-coach/fitness-coach.json").readAllBytes(),
                StandardCharsets.UTF_8);

        WorkflowDefinition wf = parser.parse(json);

        assertThat(wf.getId()).isEqualTo("fitness-coach");
        assertThat(wf.getName()).isEqualTo("健身教练助手");
        assertThat(wf.getNodes()).hasSize(10);
        assertThat(wf.getEdges()).hasSize(11);

        // 所有节点 id 已从键填充
        wf.getNodes().forEach((id, node) -> assertThat(node.getId()).isEqualTo(id));

        // 三种节点类型全覆盖（新版只用 LLM / TOOL / AGENTIC_LOOP）
        assertThat(wf.getNodes().values()).extracting(NodeDefinition::getType)
                .contains(NodeType.START, NodeType.LLM, NodeType.AGENTIC_LOOP, NodeType.TOOL, NodeType.END);

        NodeDefinition parse = wf.getNodes().get("parse");
        assertThat(parse.getType()).isEqualTo(NodeType.LLM);
        assertThat(parse.getModel()).isEqualTo("qwen-plus");
        assertThat(parse.getOutputSchema()).isNotNull();

        NodeDefinition report = wf.getNodes().get("report");
        assertThat(report.getType()).isEqualTo(NodeType.AGENTIC_LOOP);
        assertThat(report.getTools()).containsExactly("training_history_query", "workout_metrics");

        // 本图仅 STATIC 边
        assertThat(wf.getEdges()).extracting(EdgeDefinition::getType)
                .containsOnly(EdgeType.STATIC);
    }

    // ---------- 节点 id 填充 ----------

    @Test
    void parse_nodeIdFromKey_overridesInnerId() {
        String json = """
                { "id": "wf", "name": "t",
                  "nodes": { "real": { "type": "START", "id": "wrong" } },
                  "edges": [] }
                """;
        WorkflowDefinition wf = parser.parse(json);
        assertThat(wf.getNodes().get("real").getId()).isEqualTo("real");
    }

    // ---------- 错误分支 ----------

    @Test
    void parse_malformedJson_throwsWithClearMessage() {
        assertThatThrownBy(() -> parser.parse("{ not json "))
                .isInstanceOf(WorkflowParseException.class)
                .hasMessageContaining("JSON 解析失败");
    }

    @Test
    void parse_blankJson_throws() {
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(WorkflowParseException.class)
                .hasMessageContaining("JSON 不能为空");
    }

    @Test
    void parse_invalidEnum_throwsUnknownType() {
        String json = """
                { "id": "wf", "nodes": { "a": { "type": "FOO" } }, "edges": [] }
                """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(WorkflowParseException.class)
                .hasMessageContaining("未知的 type")
                .hasMessageContaining("FOO");
    }

    @Test
    void parse_unknownTopLevelField_throws() {
        String json = """
                { "id": "wf", "edgs": [] }
                """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(WorkflowParseException.class)
                .hasMessageContaining("未知字段");
    }
}
