package com.agentflow.ability.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T5.1 工具注册中心验收测试。
 *
 * <p>验收依据（任务拆解 T5.1）：注册→查询→调用闭环；重名注册报错；不存在查询/调用明确报错。
 */
class ToolRegistryTest {

    private final ToolRegistry registry = new ToolRegistry(null); // 本测试不涉及入参校验（T5.3 另有测试）

    private static ToolDescriptor tool(String name, ToolInvoker invoker) {
        return new ToolDescriptor(name, "工具 " + name, null, invoker);
    }

    @Test
    void registerThenGet_returnsSameDescriptor() {
        ToolDescriptor d = tool("calc", args -> 7);
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
        ToolDescriptor first = tool("calc", args -> 1);
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

        Set<String> names = registry.getAll().stream().map(ToolDescriptor::getName).collect(java.util.stream.Collectors.toSet());
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
        ToolDescriptor d = tool("x", args -> 1);
        assertThat(d.getTimeoutMs()).isEqualTo(ToolDescriptor.DEFAULT_TIMEOUT_MS);
    }
}