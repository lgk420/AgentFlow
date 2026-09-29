package com.agentflow.ability.tool.scenarios;

import java.util.List;

import com.agentflow.ability.rag.StubRagRetriever;
import com.agentflow.ability.rag.dto.RagChunk;
import com.agentflow.ability.tool.scenarios.fitness.FitnessTools;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * knowledge_search 单测（检索替身，不碰向量库）。
 *
 * <p>覆盖：多块拼成纯文本（不是 chunks JSON）、query/topK/collection 原样传给检索器、
 * topK 缺省值、一条没命中时给一句明确的"没有"（不然模型拿到空串会自己编）。
 *
 * <p>redis 传 null——这条路径不碰存储。
 */
class KnowledgeSearchToolTest {

    private static RagChunk chunk(String content) {
        return new RagChunk(content, 0.9);
    }

    @Test
    void joinsChunkContents_asPlainText_andPassesSearchArgs() {
        StubRagRetriever retriever = new StubRagRetriever(
                List.of(chunk("史密斯深蹲（下肢）\n起始：杠铃置于上斜方肌…"), chunk("硬拉（后链）…")));
        FitnessTools tools = new FitnessTools(null, retriever);

        String out = tools.knowledgeSearch("史密斯深蹲 动作要领", 2);

        assertThat(out).contains("史密斯深蹲").contains("硬拉");
        assertThat(out).doesNotContain("{").doesNotContain("score"); // 纯文本，不是给模型解 JSON
        assertThat(retriever.getLastQuery()).isEqualTo("史密斯深蹲 动作要领");
        assertThat(retriever.getLastTopK()).isEqualTo(2);
        assertThat(retriever.getLastCollection()).isEqualTo("workout_kb");
    }

    @Test
    void topKDefaults_whenNotGiven() {
        StubRagRetriever retriever = new StubRagRetriever(List.of(chunk("x")));

        new FitnessTools(null, retriever).knowledgeSearch("q", null);

        assertThat(retriever.getLastTopK()).isEqualTo(3);
    }

    @Test
    void noHit_saysSoExplicitly() {
        FitnessTools tools = new FitnessTools(null, new StubRagRetriever(List.of()));

        assertThat(tools.knowledgeSearch("保加利亚分腿蹲", null)).contains("没有");
    }
}
