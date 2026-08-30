package com.agentflow.api;

import java.util.Map;

/**
 * POST /runs 请求体：引用已存储的工作流 + 本次运行输入。
 */
public class RunRequest {

    /**
     * 要运行的工作流 id（须已通过 POST /workflows 入库，取最新版本）。
     */
    private String workflowId;

    /**
     * 本次运行的用户输入（模板 {@code {{inputs.x}}} 从这里取）；可缺省。
     */
    private Map<String, Object> inputs;

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public Map<String, Object> getInputs() {
        return inputs;
    }

    public void setInputs(Map<String, Object> inputs) {
        this.inputs = inputs;
    }
}
