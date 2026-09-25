package com.agentflow.engine.parse;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.agentflow.engine.model.definition.EdgeDefinition;
import com.agentflow.engine.model.definition.NodeType;
import com.agentflow.engine.model.definition.WorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T10.3 对话版健身教练工作流的结构验收——解析 + 校验通过，且形状符合设计。
 *
 * <p>重点覆盖：新增的 {@link NodeType#MEMORY_WRITE} 能过 config 字段白名单、
 * 两条 LLM_DYNAMIC 出边成对（分类 + 追问），以及两条分支各自带一个记忆写入节点。
 *
 * <p>这里只验结构（不跑 LLM）——真正的多轮效果由 {@code fitness-coach-chat} 的端到端运行验证。
 */
class FitnessCoachChatWorkflowTest {

    private static final String FIXTURE = "/testdata/workflows/fitness-coach-chat/fitness-coach-chat.json";

    private WorkflowDefinition load() throws Exception {
        String json = new String(
                getClass().getResourceAsStream(FIXTURE).readAllBytes(), StandardCharsets.UTF_8);
        return new GraphParser(new ObjectMapper()).parse(json);
    }

    @Test
    void parsesAndPassesValidation() throws Exception {
        WorkflowDefinition wf = load();

        List<String> errors = new GraphValidator().validate(wf);

        assertThat(errors).isEmpty();
    }

    @Test
    void hasBothChatBranches() throws Exception {
        WorkflowDefinition wf = load();

        List<EdgeDefinition> dynamic = wf.getEdges().stream()
                .filter(e -> e.getType() == com.agentflow.engine.model.definition.EdgeType.LLM_DYNAMIC)
                .toList();

        assertThat(dynamic).hasSize(2);
        assertThat(dynamic).extracting(EdgeDefinition::getTo).containsExactlyInAnyOrder("parse", "answer");
        assertThat(dynamic).allSatisfy(edge -> assertThat(edge.getLabel()).isNotBlank());
    }

    /** 两条分支各写各的记忆——避免用同一个节点去引用只有一条分支才有的输出。 */
    @Test
    void eachBranchHasItsOwnMemoryWrite() throws Exception {
        WorkflowDefinition wf = load();

        assertThat(wf.getNodes().get("remember_log").getType()).isEqualTo(NodeType.MEMORY_WRITE);
        assertThat(wf.getNodes().get("remember_chat").getType()).isEqualTo(NodeType.MEMORY_WRITE);

        // 写的是各自分支的回复：coach → remember_log，answer → remember_chat
        assertThat(hasStaticEdge(wf, "coach", "remember_log")).isTrue();
        assertThat(hasStaticEdge(wf, "answer", "remember_chat")).isTrue();
        assertThat(hasStaticEdge(wf, "remember_log", "end")).isTrue();
        assertThat(hasStaticEdge(wf, "remember_chat", "end")).isTrue();
    }

    /** 追问分支的 prompt 读会话记忆——没有它，"多轮"就无从谈起。 */
    @Test
    void answerBranchReadsMemory() throws Exception {
        WorkflowDefinition wf = load();

        assertThat(wf.getNodes().get("answer").getPrompt()).contains("{{memory.historyText}}");
        assertThat(wf.getNodes().get("coach").getPrompt()).contains("{{memory.historyText}}");
    }

    private static boolean hasStaticEdge(WorkflowDefinition wf, String from, String to) {
        return wf.getEdges().stream().anyMatch(e -> from.equals(e.getFrom()) && to.equals(e.getTo()));
    }
}
