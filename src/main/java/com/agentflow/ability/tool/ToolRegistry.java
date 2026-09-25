package com.agentflow.ability.tool;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 工具注册中心（T5.1，架构 9）——工具的"名录 + 调用入口"，全注册中心的存储与查询。
 *
 * <p>三种注册来源（架构 9.2）：注解扫描（T5.2）、编程式 register、MCP 同步（T5.5）都汇聚到这里。
 * 执行器 / AgenticLoop 只依赖它：按 {@link #get} 查 {@link ToolDescriptor} → {@code invoker.invoke()}。
 *
 * <p><b>并发契约</b>：存储用 {@link ConcurrentHashMap}，register 用 {@code putIfAbsent} 保证并发注册同名
 * 只有一个成功；运行期动态注册（T5.6）与并发调用（T4.5 循环）互不阻塞。
 *
 * <p><b>Bean 化（T4.3 起）</b>：T5.1 决策"tool 包暂不加 @Component"（当时注册来源未出现、无消费者）；
 * T4.3 {@code AgenticLoopExecutor} 成为首个真实消费者，注入需要它是 Spring 单例——注册来源（T5.2/5.5/编程式）
 * 仍按原计划汇聚到这一个 bean。
 */
@Component
public class ToolRegistry {

    private final Map<String, ToolDescriptor> tools = new ConcurrentHashMap<>();

    /**
     * 入参校验器（T5.3）；null 表示跳过校验（仅测试用，生产由 Spring 注入）。
     */
    private final ToolSchemaValidator validator;

    public ToolRegistry(ToolSchemaValidator validator) {
        this.validator = validator;
    }

    /**
     * 注册工具。重名抛 {@link ToolConflictException}；并发注册同名只有一个成功。
     */
    public void register(ToolDescriptor descriptor) {
        String name = descriptor.getName();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        ToolDescriptor previous = tools.putIfAbsent(name, descriptor);
        if (previous != null) {
            throw new ToolConflictException(name);
        }
    }

    /**
     * 按名字查询；不存在返回 null。
     */
    public ToolDescriptor get(String name) {
        return tools.get(name);
    }

    /**
     * 是否存在。
     */
    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /**
     * 列出全部工具（快照，注册顺序不保证）。
     */
    public Collection<ToolDescriptor> getAll() {
        return tools.values();
    }

    /**
     * 注销工具；不存在静默返回（幂等）。
     */
    public boolean remove(String name) {
        return tools.remove(name) != null;
    }

    /**
     * 按名字调用工具；不存在抛明确异常。有参数 schema 时先校验入参再执行（T5.3）。
     */
    public Object invoke(String name, Map<String, Object> args) {
        ToolDescriptor descriptor = tools.get(name);
        if (descriptor == null) {
            throw new IllegalArgumentException("工具不存在: " + name);
        }
        if (validator != null && descriptor.getParameters() != null) {
            validator.validate(name, descriptor.getParameters(), args);
        }
        return descriptor.getInvoker().invoke(args);
    }

    /**
     * 当前已注册工具数。
     */
    public int size() {
        return tools.size();
    }
}