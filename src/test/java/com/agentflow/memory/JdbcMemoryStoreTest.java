package com.agentflow.memory;

import java.util.List;
import java.util.UUID;

import com.agentflow.agent.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T10.1 记忆存储集成测试——打真实 Postgres（compose 环境）。
 *
 * <p><b>运行前提</b>：compose 的 postgres 在线（{@code bash scripts/dev.sh up}）。
 * 建表由 {@code resources/schema.sql} 在应用启动时完成（{@code spring.sql.init.mode=always}）。
 *
 * <p><b>每个用例用独立的 sessionId</b>（{@code test-<uuid>}），互不干扰，也在跑完后清理，
 * 不会污染真实会话的数据。
 */
@SpringBootTest
class JdbcMemoryStoreTest {

    @Autowired
    private MemoryStore memoryStore;

    private String sessionId;

    @BeforeEach
    void setUp() {
        sessionId = "test-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        memoryStore.clear(sessionId);
    }

    private void appendTurn(String runId, String userText, String assistantText) {
        memoryStore.append(sessionId, runId, List.of(
                ChatMessage.user(userText),
                ChatMessage.assistant(assistantText, List.of())));
    }

    @Test
    void append_thenHistory_returnsInInsertionOrder() {
        appendTurn("run-1", "我这周练了腿和胸", "【报告】本周训练量…");
        appendTurn("run-2", "换个强度小点的方案", "好，那深蹲降到…");

        List<ChatMessage> history = memoryStore.history(sessionId, 0);

        assertThat(history).hasSize(4);
        assertThat(history).extracting(ChatMessage::getContent)
                .containsExactly("我这周练了腿和胸", "【报告】本周训练量…", "换个强度小点的方案", "好，那深蹲降到…");
        assertThat(history).extracting(ChatMessage::getRole)
                .containsExactly(ChatMessage.Role.USER, ChatMessage.Role.ASSISTANT,
                        ChatMessage.Role.USER, ChatMessage.Role.ASSISTANT);
    }

    /**
     * 同一事务插入的多条 {@code created_at} 会完全相同（PostgreSQL 的 now() 是事务开始时间），
     * 所以顺序只能靠 id。这个用例把一轮的两条一起写入，正是在覆盖这个场景。
     */
    @Test
    void sameTransactionRows_stillOrdered_byId() {
        appendTurn("run-1", "第一条", "第二条");

        List<ChatMessage> history = memoryStore.history(sessionId, 0);

        assertThat(history).extracting(ChatMessage::getContent).containsExactly("第一条", "第二条");
    }

    @Test
    void history_limit_returnsLatestN() {
        appendTurn("run-1", "第1轮提问", "第1轮回答");
        appendTurn("run-2", "第2轮提问", "第2轮回答");
        appendTurn("run-3", "第3轮提问", "第3轮回答");

        List<ChatMessage> latest2 = memoryStore.history(sessionId, 2);

        // 取的是「最近的 2 条」，但返回时按时间正序
        assertThat(latest2).extracting(ChatMessage::getContent)
                .containsExactly("第3轮提问", "第3轮回答");
    }

    @Test
    void sessionsAreIsolated() {
        String otherSession = "test-" + UUID.randomUUID();
        try {
            appendTurn("run-1", "本会话的消息", "本会话的回复");
            memoryStore.append(otherSession, "run-9", List.of(ChatMessage.user("别的会话")));

            assertThat(memoryStore.history(sessionId, 0)).hasSize(2);
            assertThat(memoryStore.history(otherSession, 0)).hasSize(1);
            assertThat(memoryStore.history(sessionId, 0))
                    .extracting(ChatMessage::getContent).doesNotContain("别的会话");
        } finally {
            memoryStore.clear(otherSession);
        }
    }

    @Test
    void clear_removesOnlyThatSession() {
        String otherSession = "test-" + UUID.randomUUID();
        try {
            appendTurn("run-1", "本会话的消息", "本会话的回复");
            memoryStore.append(otherSession, "run-9", List.of(ChatMessage.user("别的会话")));

            memoryStore.clear(sessionId);

            assertThat(memoryStore.history(sessionId, 0)).isEmpty();
            assertThat(memoryStore.history(otherSession, 0)).hasSize(1);
        } finally {
            memoryStore.clear(otherSession);
        }
    }

    @Test
    void emptySession_returnsEmptyList() {
        assertThat(memoryStore.history(sessionId, 0)).isEmpty();
    }
}
