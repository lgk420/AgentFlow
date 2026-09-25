package com.agentflow.runtime.stream;

/**
 * 一次运行的进度事件（T10.4）——推给 SSE 订阅者的载荷。
 *
 * <p>与 Redis 上的 {@code EventBus} 事件（RunStarted / NodeReady / RunCompleted）是**两回事**：
 * 那是驱动执行的<b>工作流内部消息</b>（有消费组、会被 worker 竞争消费）；
 * 本类是<b>给外部看的进度</b>，只在进程内广播，不参与执行。
 */
public record RunProgress(String runId, Type type, String nodeId, String status, Object output, String error) {

    public enum Type {
        /** 一个节点执行完成（含它的输出）。 */
        NODE_COMPLETED,
        /** 整个运行到达终态（SUCCEEDED / FAILED），推送后订阅方可断开。 */
        RUN_COMPLETED
    }

    public static RunProgress nodeCompleted(String runId, String nodeId, Object output) {
        return new RunProgress(runId, Type.NODE_COMPLETED, nodeId, "SUCCEEDED", output, null);
    }

    public static RunProgress runCompleted(String runId, String status, String error) {
        return new RunProgress(runId, Type.RUN_COMPLETED, null, status, null, error);
    }
}
