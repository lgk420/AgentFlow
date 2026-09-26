package com.agentflow.engine.scheduler;

import java.util.List;
import java.util.Map;

import com.agentflow.ability.llm.StubLlmClient;
import com.agentflow.engine.model.definition.EdgeDefinition;
import com.agentflow.engine.model.definition.EdgeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T4.4 LlmRouter 单元测试——LLM_DYNAMIC 选边 + 非法 nextNode fallback。
 *
 * <p>覆盖：模型返回合法 nextNode 选中对应目标；返回非法 nextNode / 缺 nextNode → fallback 第一条候选；
 * 无候选边 → 明确报错。
 *
 * <p><b>「模型返回非 JSON」不在这里测</b>——那是 {@code chatStructured} 的实现细节（JSON 解析），
 * 已由 {@code SpringAiLlmClientChatStructuredTest.invalidJson_throws} 直接覆盖真实现。
 * 这里用桩返回预置 Map，只验证<b>路由决策</b>本身。
 */
class LlmRouterTest {

    private final StubLlmClient gateway = new StubLlmClient();
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
        gateway.setStructuredOutput(Map.of("nextNode", "measure_parse"));
        assertThat(router.route(candidates(), "用户想看体测")).isEqualTo("measure_parse");
    }

    @Test
    void route_firstCandidate_alsoSelectable() {
        gateway.setStructuredOutput(Map.of("nextNode", "analysis_query"));
        assertThat(router.route(candidates(), "分析一下")).isEqualTo("analysis_query");
    }

    @Test
    void route_invalidNextNode_fallsBackToFirst() {
        gateway.setStructuredOutput(Map.of("nextNode", "nope"));
        assertThat(router.route(candidates(), "x")).isEqualTo("analysis_query");
    }

    @Test
    void route_missingNextNode_fallsBackToFirst() {
        gateway.setStructuredOutput(Map.of("other", 1));
        assertThat(router.route(candidates(), "x")).isEqualTo("analysis_query");
    }

    @Test
    void route_noCandidates_throws() {
        assertThatThrownBy(() -> router.route(List.of(), "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无候选边");
    }
}
