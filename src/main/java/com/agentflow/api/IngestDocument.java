package com.agentflow.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * 灌库请求体中的单篇文档（T6.5）——支持两种 JSON 形态：
 *
 * <pre>
 *   "文档正文"                                    // V1 契约：只给正文，metadata 由 API 打 collection
 *   {"text": "文档正文", "metadata": {"k": "v"}}   // 带标签：脚本灌知识库时用
 * </pre>
 *
 * <p><b>为什么两种并存</b>：T6.4 的纯文本数组用法（见《知识库灌库使用说明》）继续有效，
 * 不必同步改文档和已有的 curl 命令——新增能力，不破坏既有契约。
 *
 * <p><b>metadata 不可覆盖 collection</b>：collection 以 URL 路径为准，RagApi 合并时后写覆盖，
 * 防止请求体伪造标签绕过检索隔离（多知识库共用一张表，collection 是隔离依据）。
 */
@JsonDeserialize(using = IngestDocument.Deserializer.class)
public class IngestDocument {

    private final String text;

    private final Map<String, Object> metadata;

    public IngestDocument(String text, Map<String, Object> metadata) {
        this.text = text;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public String getText() {
        return text;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /** 按 JSON 形态分流：字符串 → 只有正文；对象 → 正文 + 标签。 */
    static class Deserializer extends JsonDeserializer<IngestDocument> {

        /** 只用于把 JsonNode 的 metadata 转成 Map（ObjectMapper 读写线程安全）。 */
        private static final ObjectMapper MAPPER = new ObjectMapper();

        @Override
        public IngestDocument deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = parser.getCodec().readTree(parser);
            if (node.isTextual()) {
                return new IngestDocument(node.asText(), Map.of());
            }
            if (node.isObject()) {
                JsonNode text = node.get("text");
                if (text == null || !text.isTextual()) {
                    throw new IllegalArgumentException("documents 元素缺少 text 字段");
                }
                Map<String, Object> metadata = node.hasNonNull("metadata")
                        ? MAPPER.convertValue(node.get("metadata"), new TypeReference<LinkedHashMap<String, Object>>() {
                        })
                        : Map.of();
                return new IngestDocument(text.asText(), metadata);
            }
            throw new IllegalArgumentException("documents 元素必须是字符串或 {text, metadata} 对象");
        }
    }
}
