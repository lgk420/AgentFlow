package com.agentflow.engine.model.definition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 节点定义。
 *
 * <p>nodes 是对象、键即节点 id，id 字段由解析器（GraphParser）从键填充；
 * JSON 里平铺的 config 字段（model/prompt/tool/topK/...）经 {@link #addConfigField} 归一化进 {@link #config}，
 * 白名单校验在 GraphValidator。
 */
public class NodeDefinition {

    /**
     * 节点 id，由解析器（GraphParser）从 nodes 的键填充（键即 id），见 DSL使用说明 3.1。
     */
    private String id;

    /**
     * 节点类型（START / END / LLM / AGENTIC_LOOP / TOOL / RAG），必填；非法值在反序列化时即抛错，见 DSL使用说明 3.2。
     */
    private NodeType type;

    /**
     * 节点输出格式声明——原始 JSON Schema 原样承载，不做语义解析；仅 LLM / AGENTIC_LOOP 可带（位置校验在 GraphValidator），见 DSL使用说明 3.1。
     */
    private OutputSchema outputSchema;

    /**
     * 归一化后的节点配置：JSON 里平铺的 config 字段（model/prompt/tool/...）经 {@link #addConfigField} 收进这里；各类型字段白名单在 GraphValidator 校验，见 DSL使用说明 3.3。
     */
    private Map<String, Object> config = new LinkedHashMap<>();

    /**
     * AGENTIC_LOOP 循环上限缺省值（防死循环兜底），见 DSL使用说明 3.2。
     */
    public static final int DEFAULT_MAX_ITERATIONS = 5;

    /**
     * RAG 缺省检索条数，见 DSL使用说明 3.2。
     */
    public static final int DEFAULT_TOP_K = 3;

    public NodeDefinition() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public NodeType getType() {
        return type;
    }

    public void setType(NodeType type) {
        this.type = type;
    }

    public OutputSchema getOutputSchema() {
        return outputSchema;
    }

    public void setOutputSchema(OutputSchema outputSchema) {
        this.outputSchema = outputSchema;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }

    // ---------- 类型化访问方法 ----------
    // 执行器 / 模板解析按节点类型读 config 时用这些方法，避免调用方散落 Object 强转与默认值处理。
    // 字段仍存于 config（扩展性/白名单校验/序列化形态不受影响）；@JsonIgnore 保证它们不参与
    // Jackson 属性绑定，config 依旧以嵌套对象导出（见 DSL 3.1 "两种写法等价"）。

    /**
     * LLM / AGENTIC_LOOP：模型名。
     */
    @JsonIgnore
    public String getModel() {
        return str("model");
    }

    /**
     * LLM：提示词（可含模板占位符）。
     */
    @JsonIgnore
    public String getPrompt() {
        return str("prompt");
    }

    /**
     * AGENTIC_LOOP：系统提示词。
     */
    @JsonIgnore
    public String getSystemPrompt() {
        return str("systemPrompt");
    }

    /**
     * AGENTIC_LOOP：本轮可用工具名列表，缺省空。
     */
    @JsonIgnore
    public List<String> getTools() {
        return strList("tools");
    }

    /**
     * AGENTIC_LOOP：循环上限，缺省 {@value #DEFAULT_MAX_ITERATIONS}。
     */
    @JsonIgnore
    public int getMaxIterations() {
        return intVal("maxIterations", DEFAULT_MAX_ITERATIONS);
    }

    /**
     * TOOL：要调用的工具名。
     */
    @JsonIgnore
    public String getTool() {
        return str("tool");
    }

    /**
     * TOOL：参数对象（值可含模板占位符），缺省空 Map。
     */
    @JsonIgnore
    public Map<String, Object> getInputs() {
        return mapVal("inputs");
    }

    /**
     * RAG：查询文本（可含模板占位符）。
     */
    @JsonIgnore
    public String getQuery() {
        return str("query");
    }

    /**
     * RAG：取前几条，缺省 {@value #DEFAULT_TOP_K}。
     */
    @JsonIgnore
    public int getTopK() {
        return intVal("topK", DEFAULT_TOP_K);
    }

    /**
     * RAG：知识库名，缺省 null（走默认库）。
     */
    @JsonIgnore
    public String getCollection() {
        return str("collection");
    }

    /**
     * MEMORY_WRITE（T10.3）：用户消息模板，缺省 null（不写这条）。
     */
    @JsonIgnore
    public String getUserMessage() {
        return str("user");
    }

    /**
     * MEMORY_WRITE（T10.3）：助手回复模板，缺省 null（不写这条）。
     */
    @JsonIgnore
    public String getAssistantReply() {
        return str("assistant");
    }

    private String str(String key) {
        Object v = config.get(key);
        return v == null ? null : v.toString();
    }

    private int intVal(String key, int defaultValue) {
        Object v = config.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return parseWholeNumber(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("config." + key + " 期望数字，实际: '" + s + "'", e);
            }
        }
        throw new IllegalArgumentException("config." + key + " 期望数字，实际: " + v);
    }

    /**
     * 解析整数、或 "3.0" 这类整数形态的浮点字符串；带小数 / 非数字抛 NumberFormatException。
     */
    private static int parseWholeNumber(String s) {
        String t = s.trim();
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException ignored) {
            // 可能是 "3.0" 这类整数形态的浮点，落到下面统一解析
        }
        double d = Double.parseDouble(t); // 非数字抛 NumberFormatException，由调用方包装
        if (d < Integer.MIN_VALUE || d > Integer.MAX_VALUE || d != Math.floor(d) || Double.isInfinite(d)) {
            throw new NumberFormatException("非整数: " + s);
        }
        return (int) d;
    }

    private List<String> strList(String key) {
        Object v = config.get(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        throw new IllegalArgumentException("config." + key + " 期望字符串数组，实际: " + v);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapVal(String key) {
        Object v = config.get(key);
        if (v == null) {
            return Map.of();
        }
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException("config." + key + " 期望对象，实际: " + v);
    }

    /**
     * 反序列化：未知平铺字段（model/prompt/tool/...）收进 config。
     */
    @JsonAnySetter
    public void addConfigField(String key, Object value) {
        config.put(key, value);
    }
}
