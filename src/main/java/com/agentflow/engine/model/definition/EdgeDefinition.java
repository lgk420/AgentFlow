package com.agentflow.engine.model.definition;

/**
 * 边定义（路由）。
 *
 * <p>type 缺省 STATIC；condition/label/description 分属不同边类型（见 DSL使用说明 4.2），
 * 白名单校验在 GraphValidator。
 */
public class EdgeDefinition {

    /**
     * 起点节点 id，必填；引用的节点必须存在（GraphValidator 校验），见 DSL使用说明 4.1。
     */
    private String from;

    /**
     * 终点节点 id，必填；不能指向 START（GraphValidator 校验），见 DSL使用说明 4.1。
     */
    private String to;

    /**
     * 边类型，缺省 STATIC，见 DSL使用说明 4.2。
     */
    private EdgeType type = EdgeType.STATIC;

    /**
     * SpEL 条件表达式，仅 CONDITIONAL 边使用（必填、非空、{@code {{}}} 结构合法；完整语法校验在 P2 与求值器一起做），见 DSL使用说明 4.2。
     */
    private String condition;

    /**
     * 路由选项名，仅 LLM_DYNAMIC 边使用（必填，作为候选边之一交给模型决策），见 DSL使用说明 4.2。
     */
    private String label;

    /**
     * 路由选项说明，仅 LLM_DYNAMIC 边使用（可选，辅助模型理解该分支语义），见 DSL使用说明 4.2。
     */
    private String description;

    public EdgeDefinition() {
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public EdgeType getType() {
        return type;
    }

    public void setType(EdgeType type) {
        this.type = type;
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
