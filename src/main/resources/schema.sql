-- 业务表 DDL（T10.1 记忆），随应用启动执行（spring.sql.init.mode=always）。
-- 全部 IF NOT EXISTS，可重复执行。
--
-- 存储分层（架构约定）：
--   运行态（跑完就没用）→ Redis：checkpoint / 事件流 / 死信
--   业务数据（跨运行存活）→ Postgres：本表、以及向量表 vector_store
-- 本表是项目第一张自己管的业务表；vector_store 由 Spring AI 自动建。

-- 对话记忆：跨会话存活的对话历史，供多轮追问时取回上下文。
CREATE TABLE IF NOT EXISTS conversation_memory (
    id         BIGSERIAL   PRIMARY KEY,
    -- 会话标识：由客户端生成并保持（见 MemoryStore 注释），服务端只校验不生成
    session_id VARCHAR(64) NOT NULL,
    -- 产生这条记忆的那次运行，用于溯源（可跳去查该次运行的节点输出 / 将来的 trace）
    run_id     VARCHAR(64),
    -- user / assistant
    role       VARCHAR(16) NOT NULL,
    content    TEXT        NOT NULL,
    -- 仅供展示「什么时候说的」；**排序一律用 id**——
    -- PostgreSQL 的 now() 返回事务开始时间，同一事务内多条插入的 created_at 完全相同，
    -- 拿它排序会得到错误的顺序。
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 取某会话最近 N 条（ORDER BY id DESC）走这个索引
CREATE INDEX IF NOT EXISTS idx_memory_session ON conversation_memory (session_id, id DESC);
