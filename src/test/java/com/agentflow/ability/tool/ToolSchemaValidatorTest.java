package com.agentflow.ability.tool;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T5.3 入参 JSON Schema 校验验收测试。
 *
 * <p>验收依据（任务拆解 T5.3）：缺必填 / 类型错 → 明确报错；供 LLM 的 schema 与校验用同一份
 * （这里直接喂 {@link ToolDescriptor#getParameters()}，见 {@link #registryInvoke_validatesBeforeExecuting}）。
 */
class ToolSchemaValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolSchemaValidator validator = new ToolSchemaValidator(mapper);

    private ObjectNode schemaWithIntProp(String propName, boolean required) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject(propName).put("type", "integer");
        if (required) {
            schema.putArray("required").add(propName);
        }
        return schema;
    }

    @Test
    void validArgs_pass() {
        assertThatCode(() -> validator.validate("add", schemaWithIntProp("a", true), Map.of("a", 2)))
                .doesNotThrowAnyException();
    }

    @Test
    void missingRequired_throws() {
        assertThatThrownBy(() -> validator.validate("add", schemaWithIntProp("a", true), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("add")
                .hasMessageContaining("入参校验失败");
    }

    @Test
    void typeMismatch_throws() {
        assertThatThrownBy(() -> validator.validate("add", schemaWithIntProp("a", false), Map.of("a", "not-a-number")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("入参校验失败");
    }

    @Test
    void enumMismatch_throws() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode level = schema.putObject("properties").putObject("level");
        level.put("type", "string");
        ArrayNode enumVals = level.putArray("enum");
        enumVals.add("LOW").add("HIGH");

        assertThatThrownBy(() -> validator.validate("level", schema, Map.of("level", "MID")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("入参校验失败");
    }

    @Test
    void nullSchema_noop() {
        assertThatCode(() -> validator.validate("t", null, Map.of("x", 1))).doesNotThrowAnyException();
    }

    @Test
    void nullArgs_treatedAsEmpty() {
        assertThatThrownBy(() -> validator.validate("add", schemaWithIntProp("a", true), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registryInvoke_validatesBeforeExecuting() {
        ToolRegistry registry = new ToolRegistry(validator);
        registry.register(new ToolDescriptor("add", "加法", schemaWithIntProp("a", true), args -> 1));

        assertThatThrownBy(() -> registry.invoke("add", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("入参校验失败");
        assertThat(registry.invoke("add", Map.of("a", 2))).isEqualTo(1);
    }
}
