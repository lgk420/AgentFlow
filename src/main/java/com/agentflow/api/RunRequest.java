package com.agentflow.api;

import java.util.Map;

/**
 * POST /runs 请求体：引用已存储的工作流 + 本次运行输入（+ 可选会话标识）。
 */
public class RunRequest {

    /**
     * 要运行的工作流 id（须已通过 POST /workflows 入库，取最新版本）。
     */
    private String workflowId;

    /**
     * 会话标识（T10.2）——<b>可选</b>，由客户端生成并保持（如前端 {@code crypto.randomUUID()} 存 localStorage）。
     *
     * <p>传了就启用记忆：模板可读 {@code {{memory.history}}}，工作流可写记忆。
     * <b>不传即无记忆的单次运行</b>，行为与加记忆之前完全一致——所以这是向后兼容的扩展。
     */
    private String sessionId;

    /**
     * 本次运行的用户输入（模板 {@code {{inputs.x}}} 从这里取）；可缺省。
     */
    private Map<String, Object> inputs;

    /**
     * 是否等待到运行结束（T10.4）。<b>缺省 true</b>——保持「阻塞返回最终状态」的既有语义。
     *
     * <p>置 false 时立刻返回刚创建的 RUNNING 状态（含 runId），客户端拿 runId 去
     * {@code GET /runs/{runId}/stream} 订阅进度。没有这个开关，流式订阅就没意义——
     * 等 POST 返回时运行早结束了。
     */
    private boolean wait = true;

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Map<String, Object> getInputs() {
        return inputs;
    }

    public void setInputs(Map<String, Object> inputs) {
        this.inputs = inputs;
    }

    public boolean isWait() {
        return wait;
    }

    public void setWait(boolean wait) {
        this.wait = wait;
    }
}
