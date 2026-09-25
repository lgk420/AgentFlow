package com.agentflow.ability.tool;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

/**
 * 工具入参 JSON Schema 校验器（T5.3，架构 9.3"一套 schema 两个用途"的校验侧）。
 *
 * <p>用 {@link ToolDescriptor#getParameters()} 校验调用方传入的 args——缺必填 / 类型错 → 明确报错
 * （聚合全部错误、含工具名）。schema 为 null 时不校验（no-op）。挂在 {@link ToolRegistry#invoke}（调用咽喉）。
 *
 * <p>networknt 用 <b>1.1.0</b>（Jackson 2 原生，经典 API）；3.x 基于 Jackson 3（tools.jackson）与项目不兼容
 * （pom 注释 + QA 记录）。
 */
@Component
public class ToolSchemaValidator {

    private final ObjectMapper mapper;
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    public ToolSchemaValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 校验参数；非法抛 {@link IllegalArgumentException}（工具名 + 全部错误），合法静默返回。
     *
     * @param toolName 工具名（错误消息用）
     * @param schema   参数 JSON Schema；null 表示不校验
     * @param args     调用参数（null 视为空 Map）
     */
    public void validate(String toolName, JsonNode schema, Map<String, Object> args) {
        if (schema == null) {
            return;
        }
        JsonSchema jsonSchema = factory.getSchema(schema);
        Set<ValidationMessage> errors = jsonSchema.validate(mapper.valueToTree(args == null ? Map.of() : args));
        if (!errors.isEmpty()) {
            String detail = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.joining("; "));
            throw new IllegalArgumentException("工具 '" + toolName + "' 入参校验失败: " + detail);
        }
    }
}
