package com.agentflow.ability.memory;

import java.util.List;
import java.util.Locale;

import com.agentflow.ability.llm.ChatMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 基于 Postgres 的对话记忆实现（T10.1）——唯一接 {@code conversation_memory} 表的地方。
 *
 * <p><b>为什么存 Postgres 而不是 Redis</b>：记忆跨运行存活、将来要按内容查、还要和
 * pgvector 同库做向量记忆——按项目的存储分层约定（运行态→Redis，业务数据→Postgres），
 * 它属于业务数据。表结构见 {@code resources/schema.sql}。
 *
 * <p><b>排序一律用 id</b>（自增序列），不用 {@code created_at}——PostgreSQL 的 {@code now()}
 * 返回事务开始时间，同一事务内多条插入时间戳相同。见 {@link MemoryStore} 的说明。
 */
@Component
public class JdbcMemoryStore implements MemoryStore {

    private static final String INSERT_SQL = """
            INSERT INTO conversation_memory (session_id, run_id, role, content)
            VALUES (?, ?, ?, ?)
            """;

    /** 取最近 limit 条；子查询倒序 + 外层正序，一条 SQL 拿到「最近的 N 条按时间正序」。 */
    private static final String SELECT_RECENT_SQL = """
            SELECT role, content FROM (
                SELECT id, role, content FROM conversation_memory
                WHERE session_id = ?
                ORDER BY id DESC
                LIMIT ?
            ) recent
            ORDER BY id
            """;

    private static final String SELECT_ALL_SQL = """
            SELECT role, content FROM conversation_memory
            WHERE session_id = ?
            ORDER BY id
            """;

    private static final String CLEAR_SQL = "DELETE FROM conversation_memory WHERE session_id = ?";

    private static final RowMapper<ChatMessage> MESSAGE_MAPPER = (rs, rowNum) -> switch (rs.getString("role")) {
        case "user" -> ChatMessage.user(rs.getString("content"));
        case "assistant" -> ChatMessage.assistant(rs.getString("content"), List.of());
        default -> throw new IllegalStateException("未知的记忆角色：" + rs.getString("role"));
    };

    private final JdbcTemplate jdbc;

    public JdbcMemoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(String sessionId, String runId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        // 批量插入（一次往返）。注意：未包事务——极端情况下可能只写入一轮对话的前半，
        // 但记忆是「尽力而为」的数据，残缺一轮不会让系统出错（表现为助手没回这句）。
        jdbc.batchUpdate(INSERT_SQL, messages, messages.size(), (ps, message) -> {
            ps.setString(1, sessionId);
            ps.setString(2, runId);
            ps.setString(3, roleName(message));
            ps.setString(4, message.getContent());
        });
    }

    @Override
    public List<ChatMessage> history(String sessionId, int limit) {
        if (limit > 0) {
            return jdbc.query(SELECT_RECENT_SQL, MESSAGE_MAPPER, sessionId, limit);
        }
        return jdbc.query(SELECT_ALL_SQL, MESSAGE_MAPPER, sessionId);
    }

    @Override
    public void clear(String sessionId) {
        jdbc.update(CLEAR_SQL, sessionId);
    }

    /** 落库用的小写角色名（user / assistant），与 schema.sql 的注释一致。 */
    private static String roleName(ChatMessage message) {
        return message.getRole().name().toLowerCase(Locale.ROOT);
    }
}
