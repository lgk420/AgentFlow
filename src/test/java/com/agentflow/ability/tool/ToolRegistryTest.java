package com.agentflow.ability.tool;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.agentflow.ability.tool.dto.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T5.1 / T5.3 工具注册中心验收测试。
 *
 * <p>验收依据（任务拆解 T5.1 / T5.3）：注册→查询→调用闭环；重名注册报错；不存在查询/调用明确报错；
 * 有 parameters 的工具在调用前<b>先校验入参</b>（缺必填 / 类型错 / 枚举不匹配 → 明确报错）。
 */
class ToolRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolRegistry registry = new ToolRegistry(mapper);

    private static ToolDefinition tool(String name, ToolInvoker invoker) {
        return new ToolDefinition(name, "工具 " + name, null, invoker);
    }

    @Test
    void registerThenGet_returnsSameDescriptor() {
        ToolDefinition d = tool("calc", args -> 7);
        registry.register(d);

        assertThat(registry.get("calc")).isSameAs(d);
        assertThat(registry.contains("calc")).isTrue();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void getMissing_returnsNull() {
        assertThat(registry.get("nope")).isNull();
        assertThat(registry.contains("nope")).isFalse();
    }

    @Test
    void invoke_runsTheToolInvoker() {
        registry.register(tool("calc", args -> {
            int a = ((Number) args.get("a")).intValue();
            int b = ((Number) args.get("b")).intValue();
            return a + b;
        }));

        Object result = registry.invoke("calc", Map.of("a", 2, "b", 3));
        assertThat(result).isEqualTo(5);
    }

    @Test
    void invokeMissing_throws() {
        assertThatThrownBy(() -> registry.invoke("nope", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工具不存在");
    }

    @Test
    void registerDuplicateName_throws() {
        ToolDefinition first = tool("calc", args -> 1);
        registry.register(first);

        assertThatThrownBy(() -> registry.register(tool("calc", args -> 2)))
                .isInstanceOf(ToolConflictException.class)
                .hasMessageContaining("calc");
        // 第二个没注册上，仍是第一个
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.get("calc")).isSameAs(first);
    }

    @Test
    void registerBlankName_throws() {
        assertThatThrownBy(() -> registry.register(tool("  ", args -> 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工具名不能为空");
    }

    @Test
    void getAll_returnsAllRegistered() {
        registry.register(tool("a", args -> 1));
        registry.register(tool("b", args -> 2));
        registry.register(tool("c", args -> 3));

        Set<String> names = registry.getAll().stream().map(ToolDefinition::getName).collect(java.util.stream.Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void remove_thenGone() {
        registry.register(tool("calc", args -> 1));

        assertThat(registry.remove("calc")).isTrue();
        assertThat(registry.get("calc")).isNull();
        // 删除后再注册同名可行
        registry.register(tool("calc", args -> 2));
        assertThat(registry.get("calc")).isNotNull();
    }

    @Test
    void removeMissing_returnsFalse() {
        assertThat(registry.remove("nope")).isFalse();
    }

    @Test
    void concurrentRegisterSameName_onlyOneWins() throws InterruptedException {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        AtomicReference<Integer> successCount = new AtomicReference<>(0);

        for (int i = 0; i < threads; i++) {
            int index = i;
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    registry.register(tool("calc", args -> index));
                    successCount.accumulateAndGet(1, Integer::sum);
                } catch (Throwable t) {
                    firstError.compareAndSet(null, t);
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // 只有一个成功，其余全部 ToolConflictException
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(firstError.get()).isInstanceOf(ToolConflictException.class);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void descriptorDefaultTimeout() {
        ToolDefinition d = tool("x", args -> 1);
        assertThat(d.getTimeoutMs()).isEqualTo(ToolDefinition.DEFAULT_TIMEOUT_MS);
    }

    // ═══════════════════ T5.3 入参 JSON Schema 校验 ═══════════════════

    /**
     * 注册一个带 schema 的工具。校验是 {@code invoke} 的一步，所以测试都从 invoke 走，
     * 不穿透到私有方法。
     */
    private void registerWithSchema(String name, ObjectNode schema) {
        registry.register(new ToolDefinition(name, "工具 " + name, schema, args -> "ok"));
    }

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
    void missingRequired_throws() {
        registerWithSchema("add", schemaWithIntProp("a", true));

        assertThatThrownBy(() -> registry.invoke("add", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("add")
                .hasMessageContaining("入参校验失败");
    }

    @Test
    void typeMismatch_throws() {
        registerWithSchema("add", schemaWithIntProp("a", false));

        assertThatThrownBy(() -> registry.invoke("add", Map.of("a", "not-a-number")))
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
        registerWithSchema("level", schema);

        assertThatThrownBy(() -> registry.invoke("level", Map.of("level", "MID")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("入参校验失败");
    }

    @Test
    void nullSchema_noop() {
        registerWithSchema("t", null);

        assertThatCode(() -> registry.invoke("t", Map.of("x", 1))).doesNotThrowAnyException();
    }

    @Test
    void nullArgs_treatedAsEmpty() {
        registerWithSchema("add", schemaWithIntProp("a", true));

        assertThatThrownBy(() -> registry.invoke("add", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 校验通过后，工具真的被执行了（不只是"没拦住"）。
     */
    @Test
    void registryInvoke_validatesBeforeExecuting() {
        registry.register(new ToolDefinition("add", "加法", schemaWithIntProp("a", true), args -> 1));

        assertThatThrownBy(() -> registry.invoke("add", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("入参校验失败");
        assertThat(registry.invoke("add", Map.of("a", 2))).isEqualTo(1);
    }
}