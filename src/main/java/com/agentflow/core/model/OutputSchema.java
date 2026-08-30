package com.agentflow.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 节点输出格式声明——原始 JSON Schema，原样承载，不做语义解析。
 *
 * <p>P1 只负责存储与位置校验（仅 LLM / AGENTIC_LOOP 可带，见 DSL使用说明 3.3）；
 * 合法性校验（json-schema-validator）在 P5。
 *
 * <p>序列化：@JsonValue 直接输出原始 schema，不带包装；
 * 反序列化：@JsonCreator 把整个 outputSchema 对象原样收进 schema。
 */
public class OutputSchema {

    /**
     * 原始 JSON Schema 节点，原样承载、不做语义解析；合法性校验（json-schema-validator）在 P5。
     */
    private JsonNode schema;

    public OutputSchema() {
    }

    @JsonCreator
    public OutputSchema(JsonNode schema) {
        this.schema = schema;
    }

    @JsonValue
    public JsonNode getSchema() {
        return schema;
    }

    public void setSchema(JsonNode schema) {
        this.schema = schema;
    }
}
