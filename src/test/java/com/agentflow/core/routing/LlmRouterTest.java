package com.agentflow.core.routing;

import java.util.List;

import com.agentflow.agent.StubLlmGateway;
import com.agentflow.core.model.EdgeDefinition;
import com.agentflow.core.model.EdgeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T4.4 LlmRouter 单元测试——LLM_DYNAMIC 选边 + 非法 nextNode fallback。
 *
 * <p>覆盖：模型返回合法 nextNode 选中对应目标；返回非法 nextNode / 缺 nextNode → fallback 第一条候选；
 * 模型返回非 JSON → 明确报错；无候选边 → 明确报错。
 */
class LlmRouterTest {

    private final StubLlmGateway gateway = new StubLlmGateway();
    private final LlmRouter router = new LlmRouter(gateway, new ObjectMapper());

    /** 三条 LLM_DYNAMIC 候选：analysis_query / measure_parse / plan（legacy classify 同构）。 */
    private static List<EdgeDefinition> candidates() {
        return List.of(
                dynamicEdge("analysis_query", "训练分析", "用户想分析训练记录或评估训练表现"),
                dynamicEdge("measure_parse", "体测解读", "用户想解读体测报告"),
                dynamicEdge("plan", "计划生成", "用户想制定训练与饮食计划"));
    }

    private static EdgeDefinition dynamicEdge(String to, String label, String description) {
        EdgeDefinition e = new EdgeDefinition();
        e.setType(EdgeType.LLM_DYNAMIC);
        e.setTo(to);
        e.setLabel(label);
        e.setDescription(description);
        return e;
    }

    @Test
    void route_validNextNode_selectsThatTarget() {
        gateway.setTextOutput("{\"nextNode\":\"measure_parse\"}");
        assertThat(router.route(candidates(), "用户想看体测")).isEqualTo("measure_parse");
    }

    @Test
    void route_firstCandidate_alsoSelectable() {
        gateway.setTextOutput("{\"nextNode\":\"analysis_query\"}");
        assertThat(router.route(candidates(), "分析一下")).isEqualTo("analysis_query");
    }

    @Test
    void route_invalidNextNode_fallsBackToFirst() {
        gateway.setTextOutput("{\"nextNode\":\"nope\"}");
        assertThat(router.route(candidates(), "x")).isEqualTo("analysis_query");
    }

    @Test
    void route_missingNextNode_fallsBackToFirst() {
        gateway.setTextOutput("{\"other\":1}");
        assertThat(router.route(candidates(), "x")).isEqualTo("analysis_query");
    }

    @Test
    void route_invalidJson_throws() {
        gateway.setTextOutput("这不是 JSON");
        assertThatThrownBy(() -> router.route(candidates(), "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是合法 JSON");
    }

    @Test
    void route_noCandidates_throws() {
        assertThatThrownBy(() -> router.route(List.of(), "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无候选边");
    }
}
