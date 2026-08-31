package com.agentflow.core.state;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 一次运行的可变状态 = checkpoint 的内存形态（架构 5.4）。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code runId}：一次运行的唯一标识；</li>
 *   <li>{@code nodeOutputs}：nodeId → 节点执行结果，模板引用 {@code {{nodes.x.output.y}}} 从这里取；</li>
 *   <li>{@code version}：乐观并发控制（CAS），P3 落 Redis 时使用，P2 先占位。</li>
 * </ul>
 *
 * <p>按 QA 07 决策用传统 POJO。
 */
public class WorkflowState {

    /**
     * 一次运行的唯一标识。
     */
    private String runId;

    /**
     * 本次运行使用的工作流定义 id。
     */
    private String workflowId;

    /**
     * 运行总体状态，初始 RUNNING。
     */
    private RunStatus status = RunStatus.RUNNING;

    /**
     * 用户输入（模板 {@code {{inputs.x}}} 从这里取）。
     */
    private Map<String, Object> inputs = new LinkedHashMap<>();

    /**
     * 已执行节点的输出（模板 {@code {{nodes.x.output.y}}} 从这里取）。
     */
    private Map<String, NodeOutput> nodeOutputs = new LinkedHashMap<>();

    /**
     * 乐观并发控制版本号（P3 CAS 用，P2 先占位）。
     */
    private int version;

    /**
     * 创建时间。
     */
    private Instant createdAt;

    /**
     * 最后更新时间。
     */
    private Instant updatedAt;

    /**
     * 运行级失败原因；仅 FAILED 时有值（节点级错误在各 NodeOutput.error，这里给"为什么整体失败"）。
     */
    private String error;

    /**
     * T7.3 事件驱动：节点 → 出边解析结果（{目标: 是否 fired}）。
     * 节点执行完成（或死分支）时记录，就绪集从这些事实重算（不存可变计数器，QA 79）。
     */
    private Map<String, Map<String, Boolean>> resolvedEdges = new LinkedHashMap<>();

    /**
     * T7.3 事件驱动：死分支节点集合（所有入边都未 fired，不执行、出边按死边级联）。
     */
    private Set<String> deadNodes = new LinkedHashSet<>();

    public WorkflowState() {
    }

    /**
     * 深拷贝快照：Map 结构隔离，NodeOutput 也复制一份，防调用方通过引用改动内部状态。
     */
    public WorkflowState snapshot() {
        WorkflowState copy = new WorkflowState();
        copy.runId = runId;
        copy.workflowId = workflowId;
        copy.status = status;
        copy.inputs = new LinkedHashMap<>(inputs);
        copy.nodeOutputs = new LinkedHashMap<>();
        nodeOutputs.forEach((id, no) -> copy.nodeOutputs.put(id, new NodeOutput(no)));
        copy.version = version;
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        copy.error = error;
        // T7.3：resolvedEdges 内层 Map 也复制，防引用污染
        resolvedEdges.forEach((node, resolutions) -> copy.resolvedEdges.put(node, new LinkedHashMap<>(resolutions)));
        copy.deadNodes = new LinkedHashSet<>(deadNodes);
        return copy;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public Map<String, Object> getInputs() {
        return inputs;
    }

    public void setInputs(Map<String, Object> inputs) {
        this.inputs = inputs;
    }

    public Map<String, NodeOutput> getNodeOutputs() {
        return nodeOutputs;
    }

    public void setNodeOutputs(Map<String, NodeOutput> nodeOutputs) {
        this.nodeOutputs = nodeOutputs;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Map<String, Map<String, Boolean>> getResolvedEdges() {
        return resolvedEdges;
    }

    public void setResolvedEdges(Map<String, Map<String, Boolean>> resolvedEdges) {
        this.resolvedEdges = resolvedEdges;
    }

    public Set<String> getDeadNodes() {
        return deadNodes;
    }

    public void setDeadNodes(Set<String> deadNodes) {
        this.deadNodes = deadNodes;
    }
}
