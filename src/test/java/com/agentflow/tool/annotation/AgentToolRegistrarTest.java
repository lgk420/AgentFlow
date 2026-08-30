package com.agentflow.tool.annotation;

import java.util.List;
import java.util.Map;

import com.agentflow.tool.ToolDescriptor;
import com.agentflow.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T5.2 注解扫描验收测试（直接测扫描逻辑 {@link AgentToolRegistrar#descriptorsOf}，不依赖 Spring 上下文；
 * 上下文端到端另见 {@link AgentToolScanIntegrationTest}）。
 */
class AgentToolRegistrarTest {

    private final AgentToolRegistrar registrar = new AgentToolRegistrar(null, new ToolRegistry(null), new ObjectMapper());
    private final ToolRegistry registry = new ToolRegistry(null); // 本测试不涉及入参校验（T5.3 另有测试）

    public enum Level {
        LOW, HIGH
    }

    public static class FakeTools {
        @AgentTool(name = "add", description = "两数相加")
        public int add(int a, int b) {
            return a + b;
        }

        @AgentTool
        public String greet(String name) {
            return "hi " + name;
        }

        @AgentTool
        public String level(Level level) {
            return level.name();
        }

        @AgentTool
        public String join(List<String> items) {
            return String.join(",", items);
        }
    }

    private ToolDescriptor register(String name) {
        List<ToolDescriptor> descriptors = registrar.descriptorsOf(new FakeTools());
        ToolDescriptor d = descriptors.stream().filter(t -> t.getName().equals(name)).findFirst().orElseThrow();
        registry.register(d);
        return d;
    }

    @Test
    void descriptorsOf_convertsAnnotatedMethods() {
        List<ToolDescriptor> descriptors = registrar.descriptorsOf(new FakeTools());

        assertThat(descriptors).hasSize(4);
        ToolDescriptor add = descriptors.stream().filter(t -> t.getName().equals("add")).findFirst().orElseThrow();
        assertThat(add.getDescription()).isEqualTo("两数相加");
        // 缺省 name/description 用方法名
        ToolDescriptor greet = descriptors.stream().filter(t -> t.getName().equals("greet")).findFirst().orElseThrow();
        assertThat(greet.getDescription()).isEqualTo("greet");
    }

    @Test
    void schema_inferredFromSignature() {
        JsonNode params = register("add").getParameters();

        assertThat(params.get("type").asText()).isEqualTo("object");
        assertThat(params.get("properties").get("a").get("type").asText()).isEqualTo("integer");
        assertThat(params.get("required")).extracting(JsonNode::asText).containsExactly("a", "b");
    }

    @Test
    void schema_enum_hasEnumValues() {
        JsonNode params = register("level").getParameters();
        JsonNode enumNode = params.get("properties").get("level").get("enum");
        assertThat(enumNode).extracting(JsonNode::asText).containsExactlyInAnyOrder("LOW", "HIGH");
    }

    @Test
    void schema_listParam_hasItems() {
        JsonNode params = register("join").getParameters();
        JsonNode items = params.get("properties").get("items");
        assertThat(items.get("type").asText()).isEqualTo("array");
        assertThat(items.get("items").get("type").asText()).isEqualTo("string");
    }

    @Test
    void invoke_viaRegistry() {
        register("add");
        register("greet");

        assertThat(registry.invoke("add", Map.of("a", 2, "b", 3))).isEqualTo(5);
        assertThat(registry.invoke("greet", Map.of("name", "Bob"))).isEqualTo("hi Bob");
    }

    @Test
    void invoke_missingParam_throws() {
        register("add");
        assertThatThrownBy(() -> registry.invoke("add", Map.of("a", 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少参数: add.b");
    }

    @Test
    void invoke_numberString_coerced() {
        register("add");
        assertThat(registry.invoke("add", Map.of("a", "2", "b", "3"))).isEqualTo(5);
    }

    @Test
    void nonPublicAnnotatedMethod_skipped() {
        class PrivateTool {
            @AgentTool
            private String secret() {
                return "x";
            }
        }
        assertThat(registrar.descriptorsOf(new PrivateTool())).isEmpty();
    }
}
