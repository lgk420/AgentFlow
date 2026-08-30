package com.agentflow.core.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流定义（引擎的一等公民，JSON 可配置）。
 *
 * <p>nodes 键=节点 id；edges 保序（CONDITIONAL 多条按声明顺序求值，首个为真生效）。
 */
public class WorkflowDefinition {

    /**
     * 工作流全局唯一标识（字符集 {@code [a-zA-Z0-9][a-zA-Z0-9-_]*}，非空、不以 {@code -} 开头）；也是存储键 {@code wf:{id}:{version}} 的一部分，见 DSL使用说明 2.1。
     */
    private String id;

    /**
     * 展示名，用于 trace / 日志 / 监控，见 DSL使用说明 2.1。
     */
    private String name;

    /**
     * 版本号，正整数，默认 1。同一 id 多版本并存互不覆盖；P3 断点续跑按 run 启动时的版本取定义，见 DSL使用说明 2.1。
     */
    private int version = 1;

    /**
     * 节点集合，键=节点 id、值=节点定义；用 LinkedHashMap 保声明顺序。节点 id 由解析器（GraphParser）从键填充，见 DSL使用说明 3.1。
     */
    private Map<String, NodeDefinition> nodes = new LinkedHashMap<>();

    /**
     * 边列表，保序——CONDITIONAL 多条按声明顺序求值、首个为真生效，见 DSL使用说明 4.2。
     */
    private List<EdgeDefinition> edges = new ArrayList<>();

    public WorkflowDefinition() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Map<String, NodeDefinition> getNodes() {
        return nodes;
    }

    public void setNodes(Map<String, NodeDefinition> nodes) {
        this.nodes = nodes;
    }

    public List<EdgeDefinition> getEdges() {
        return edges;
    }

    public void setEdges(List<EdgeDefinition> edges) {
        this.edges = edges;
    }
}
