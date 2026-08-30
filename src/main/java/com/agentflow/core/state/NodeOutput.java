package com.agentflow.core.state;

/**
 * 单个节点的执行结果（架构 5.4）。
 *
 * <p>按 QA 07 决策用传统 POJO（后续 P3 会随 checkpoint 序列化）。
 */
public class NodeOutput {

    /**
     * 节点 id。
     */
    private String nodeId;

    /**
     * 节点输出（执行结果数据，类型由节点类型决定）。
     */
    private Object output;

    /**
     * 失败原因；成功时为 null。
     */
    private String error;

    /**
     * 节点执行结果状态。
     */
    private NodeStatus status;

    public NodeOutput() {
    }

    public NodeOutput(String nodeId, Object output, String error, NodeStatus status) {
        this.nodeId = nodeId;
        this.output = output;
        this.error = error;
        this.status = status;
    }

    /**
     * 拷贝构造（快照用）。
     */
    public NodeOutput(NodeOutput other) {
        this.nodeId = other.nodeId;
        this.output = other.output;
        this.error = other.error;
        this.status = other.status;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public Object getOutput() {
        return output;
    }

    public void setOutput(Object output) {
        this.output = output;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public NodeStatus getStatus() {
        return status;
    }

    public void setStatus(NodeStatus status) {
        this.status = status;
    }
}
