package com.agentflow.runtime.queue;

/**
 * 事件流与消费组常量（T7.2，架构 8）——三个流各司其职（QA 75）：
 * <ul>
 *   <li>{@link #RUN}：运行生命周期，低频，run-worker 少量实例够；</li>
 *   <li>{@link #NODE}：节点执行，唯一需横向扩容的流（同组多消费者、consumer 名唯一，QA 76）；</li>
 *   <li>{@link #TRACE}：可观测，量大但便宜且 best-effort（可落后不阻塞执行）。</li>
 * </ul>
 */
public final class Streams {

    /**
     * 运行生命周期事件（RunStarted / RunCompleted）。
     */
    public static final String RUN = "agentflow:run";

    /**
     * 节点执行事件（NodeReady）。
     */
    public static final String NODE = "agentflow:node";

    /**
     * 可观测事件（LLM/工具/节点调用，best-effort）。
     */
    public static final String TRACE = "agentflow:trace";

    /**
     * 运行处理器消费组。
     */
    public static final String RUN_WORKER = "run-worker";

    /**
     * 节点执行器消费组。
     */
    public static final String NODE_WORKER = "node-worker";

    /**
     * 观测写入消费组（T8.2 落库）。
     */
    public static final String TRACE_WRITER = "trace-writer";

    /**
     * 死信流（T7.5）：重试耗尽的消息 + 错误栈，供人工排查/告警。
     */
    public static final String DLQ = "agentflow:dlq";

    private Streams() {
    }
}
