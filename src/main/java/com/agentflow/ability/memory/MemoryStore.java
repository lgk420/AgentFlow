package com.agentflow.ability.memory;

import java.util.List;

import com.agentflow.ability.llm.dto.LlmChatMessage;

/**
 * 对话记忆存取（T10.1）——跨会话存活的对话历史，供多轮追问时取回上下文。
 *
 * <p><b>与 checkpoint 的区别</b>：checkpoint 是<b>运行态</b>（跑完就没用，存 Redis）；
 * 本接口管的是<b>业务数据</b>（跨运行存活、可查询，存 Postgres）。这是项目的存储分层约定。
 *
 * <p><b>sessionId 由客户端生成并保持</b>（如前端 {@code crypto.randomUUID()} 存 localStorage），
 * 服务端只校验不生成——因为服务端无从知道「这两个请求是同一个人接着说的」。
 * <b>不传 sessionId 即视为无记忆的单次运行</b>，调用方不受影响。
 *
 * <p><b>排序一律用 id</b>（数据库自增序列），不用 {@code created_at}：
 * PostgreSQL 的 {@code now()} 返回事务开始时间，同一事务内多条插入的时间戳完全相同。
 * 也不另存「会话内序号」——那需要读 max+1，是读-改-写竞态；要显示序号时用
 * {@code row_number() OVER (PARTITION BY session_id ORDER BY id)} 临时算。
 *
 * <p>执行器只依赖本接口，换实现、测试注入桩都只动这一个 seam（同 {@code Retriever} / {@code Reranker}）。
 */
public interface MemoryStore {

    /**
     * 追加若干条消息到会话末尾（一次调用一个事务）。
     *
     * @param sessionId 会话标识
     * @param runId     产生这些消息的运行 id，可空（用于溯源）
     * @param messages  按时间正序的消息
     */
    void append(String sessionId, String runId, List<LlmChatMessage> messages);

    /**
     * 取会话最近 limit 条消息，按时间正序返回。
     *
     * <p>只截断读取、不删数据——prompt 大小可控，同时全量数据留着供将来查询/挖掘。
     *
     * @param limit 最多取几条；≤0 表示不限制
     */
    List<LlmChatMessage> history(String sessionId, int limit);

    /**
     * 清空某个会话的全部记忆。用于「开始新话题」或测试清理。
     */
    void clear(String sessionId);
}
