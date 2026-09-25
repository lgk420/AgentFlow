package com.agentflow.runtime.event;

import java.util.Map;

/**
 * 引擎事件契约（T7.2）——typed 事件 record，带 {@code type} 判别字段（QA 72：payload 是 JSON 序列化的
 * Map，type 供消费端判别反序列化）。T7.3 的 run-worker / node-worker 按 type 分发处理。
 */
public final class Events {

    /**
     * payload 中的判别字段名。
     */
    public static final String TYPE_FIELD = "type";

    private Events() {
    }

    /**
     * 运行启动（POST /runs 发布到 {@link Streams#RUN}；
     * run-worker 消费后写初始 checkpoint + 发第一个 NodeReady）。
     */
    public record RunStarted(String type, String runId, String workflowId, Map<String, Object> inputs) {

        public static final String TYPE = "RUN_STARTED";

        public static RunStarted of(String runId, String workflowId, Map<String, Object> inputs) {
            return new RunStarted(TYPE, runId, workflowId, inputs);
        }
    }

    /**
     * 运行结束（到 END 时发布；run-worker 处理收尾）。
     */
    public record RunCompleted(String type, String runId, String status, String error) {

        public static final String TYPE = "RUN_COMPLETED";

        public static RunCompleted of(String runId, String status, String error) {
            return new RunCompleted(TYPE, runId, status, error);
        }
    }

    /**
     * 节点就绪（node-worker 消费后执行节点、更新 checkpoint、算就绪集并派发下游）。
     */
    public record NodeReady(String type, String runId, String nodeId) {

        public static final String TYPE = "NODE_READY";

        public static NodeReady of(String runId, String nodeId) {
            return new NodeReady(TYPE, runId, nodeId);
        }
    }

    /**
     * 可观测事件（kind=LLM/TOOL/NODE，data 携带具体字段；trace-writer 消费，T8 落库）。
     */
    public record Trace(String type, String kind, Map<String, Object> data) {

        public static final String TYPE = "TRACE";

        public static Trace of(String kind, Map<String, Object> data) {
            return new Trace(TYPE, kind, data);
        }
    }
}
