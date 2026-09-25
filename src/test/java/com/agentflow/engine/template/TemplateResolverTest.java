package com.agentflow.engine.template;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.agentflow.engine.model.definition.WorkflowDefinition;
import com.agentflow.engine.parse.GraphParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1.4 模板解析器验收测试。
 *
 * <p>覆盖：字段链取值、宽松空格、三种默认值 + null、默认只在缺 key 时生效、缺 key 无默认抛错、
 * {{memory...}} 未实现、非法默认值字面量、混合文本多占位符拼接、非字符串值转字符串、不匹配花括号保留原文、
 * fitness-coach 真实 prompt 端到端。
 */
class TemplateResolverTest {

    private final TemplateResolver resolver = new TemplateResolver();
    private final GraphParser parser = new GraphParser(new ObjectMapper());

    private static Map<String, Object> ctx(Map<String, Object> inputs, Map<String, Object> nodes) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("inputs", inputs == null ? Map.of() : inputs);
        context.put("nodes", nodes == null ? Map.of() : nodes);
        return context;
    }

    // ---------- 字段链取值 ----------

    @Test
    void resolve_inputsFieldChain() {
        Map<String, Object> context = ctx(Map.of("userMessage", "我想练背"), null);
        assertThat(resolver.resolve("用户说：{{inputs.userMessage}}", context))
                .isEqualTo("用户说：我想练背");
    }

    @Test
    void resolve_nodeOutputFieldChain() {
        Map<String, Object> context = ctx(null,
                Map.of("analysis_query", Map.of("output", Map.of("data", "容量 120kg"))));
        assertThat(resolver.resolve("记录：{{nodes.analysis_query.output.data}}", context))
                .isEqualTo("记录：容量 120kg");
    }

    // ---------- 宽松空格 ----------

    @Test
    void resolve_looseWhitespace() {
        Map<String, Object> context = ctx(Map.of("x", "v"), null);
        assertThat(resolver.resolve("{{ inputs.x }}", context)).isEqualTo("v");
        assertThat(resolver.resolve("{{inputs.x}}", context)).isEqualTo("v");
    }

    // ---------- 默认值 ----------

    @Test
    void resolve_defaultValues() {
        Map<String, Object> context = ctx(Map.of(), null);
        assertThat(resolver.resolve("{{inputs.from | '2026-08-01'}}", context)).isEqualTo("2026-08-01");
        assertThat(resolver.resolve("{{inputs.topK | 3}}", context)).isEqualTo("3");
        assertThat(resolver.resolve("{{inputs.auto | true}}", context)).isEqualTo("true");
        assertThat(resolver.resolve("{{inputs.d | 3.5}}", context)).isEqualTo("3.5");
    }

    @Test
    void resolve_defaultOnlyWhenMissing() {
        Map<String, Object> context = ctx(Map.of("from", "2026-08-10"), null);
        assertThat(resolver.resolve("{{inputs.from | '2026-08-01'}}", context)).isEqualTo("2026-08-10");
    }

    // ---------- 缺 key / memory / 非法字面量 ----------

    @Test
    void resolve_missingKeyWithoutDefault_throws() {
        Map<String, Object> context = ctx(Map.of(), null);
        assertThatThrownBy(() -> resolver.resolve("{{inputs.missing}}", context))
                .isInstanceOf(TemplateResolutionException.class)
                .hasMessageContaining("缺少值且无默认");
    }

    @Test
    void resolve_memory_throwsNotImplemented() {
        Map<String, Object> context = ctx(Map.of(), null);
        assertThatThrownBy(() -> resolver.resolve("{{memory.customer.profile}}", context))
                .isInstanceOf(TemplateResolutionException.class)
                .hasMessageContaining("memory")
                .hasMessageContaining("未实现");
    }

    @Test
    void resolve_invalidDefaultLiteral_throws() {
        Map<String, Object> context = ctx(Map.of(), null);
        assertThatThrownBy(() -> resolver.resolve("{{inputs.x | CN}}", context))
                .isInstanceOf(TemplateResolutionException.class)
                .hasMessageContaining("非法默认值字面量");
    }

    // ---------- 拼接与边界 ----------

    @Test
    void resolve_mixedTextAndMultiplePlaceholders() {
        Map<String, Object> context = ctx(
                Map.of("userMessage", "hi", "region", "CN"),
                Map.of("a", Map.of("output", Map.of("f", "42"))));
        String out = resolver.resolve(
                "用户={{inputs.userMessage}} 区域={{inputs.region | 'default'}} 结果={{nodes.a.output.f}}", context);
        assertThat(out).isEqualTo("用户=hi 区域=CN 结果=42");
    }

    @Test
    void resolve_typedValues_stringified() {
        Map<String, Object> context = ctx(Map.of("count", 3, "auto", false), null);
        assertThat(resolver.resolve("count={{inputs.count}} auto={{inputs.auto}}", context))
                .isEqualTo("count=3 auto=false");
    }

    @Test
    void resolve_unmatchedOpenBrace_keptAsLiteral() {
        Map<String, Object> context = ctx(Map.of("x", "v"), null);
        assertThat(resolver.resolve("文字 {{inputs.x}} 剩余 {{abc", context))
                .isEqualTo("文字 v 剩余 {{abc");
    }

    // ---------- 验收：健身 DSL 真实 prompt 端到端 ----------

    @Test
    void resolve_fitnessCoachPrompt_endToEnd() throws Exception {
        String json = new String(
                getClass().getResourceAsStream("/testdata/workflows/legacy-fitness-coach/fitness-coach.json").readAllBytes(),
                StandardCharsets.UTF_8);
        WorkflowDefinition wf = parser.parse(json);
        String prompt = wf.getNodes().get("parse").getPrompt();

        Map<String, Object> context = ctx(Map.of("userLog", "20260812背 哑铃划船 15kg×12×4"), null);
        String resolved = resolver.resolve(prompt, context);

        assertThat(resolved).contains("20260812背 哑铃划船 15kg×12×4");
        assertThat(resolved).doesNotContain("{{");
    }
}
