package com.agentflow.ability.tool;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.agentflow.ability.tool.dto.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

/**
 * 工具注册中心（T5.1，架构 9）——工具的"名录 + 调用入口"，全注册中心的存储与查询。
 *
 * <p>三种注册来源（架构 9.2）：注解扫描（T5.2）、编程式 register、MCP 同步（T5.5）都汇聚到这里。
 * 执行器 / AgenticLoop 只依赖它：按 {@link #get} 查 {@link ToolDefinition} → {@code invoker.invoke()}。
 *
 * <p><b>并发契约</b>：存储用 {@link ConcurrentHashMap}，register 用 {@code putIfAbsent} 保证并发注册同名
 * 只有一个成功；运行期动态注册（T5.6）与并发调用（T4.5 循环）互不阻塞。
 *
 * <p><b>Bean 化（T4.3 起）</b>：T5.1 决策"tool 包暂不加 @Component"（当时注册来源未出现、无消费者）；
 * T4.3 {@code AgenticLoopExecutor} 成为首个真实消费者，注入需要它是 Spring 单例——注册来源（T5.2/5.5/编程式）
 * 仍按原计划汇聚到这一个 bean。
 *
 * <p><b>入参校验（T5.3）</b>：{@link #invoke} 执行前用工具自己的 parameters 校验 args——
 * "一套 schema 两个用途"（架构 9.3）的校验侧。见 {@link #validate}。
 */
@Component
public class ToolRegistry {

    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();

    private final ObjectMapper mapper;

    /**
     * networknt 用 <b>1.1.0</b>（Jackson 2 原生，经典 API）；3.x 基于 Jackson 3（tools.jackson）
     * 与项目不兼容（pom 注释 + QA 记录）。
     */
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    public ToolRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 注册工具。重名抛 {@link ToolConflictException}；并发注册同名只有一个成功。
     */
    public void register(ToolDefinition toolDefinition) {
        String name = toolDefinition.getName();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        if (tools.putIfAbsent(name, toolDefinition) != null) {
            throw new ToolConflictException(name);
        }
    }

    /**
     * 按名字查询；不存在返回 null。
     */
    public ToolDefinition get(String name) {
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
    public Collection<ToolDefinition> getAll() {
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
        ToolDefinition toolDefinition = tools.get(name);
        if (toolDefinition == null) {
            throw new IllegalArgumentException("工具不存在: " + name);
        }
        validate(name, toolDefinition.getParameters(), args);
        return toolDefinition.getToolInvoker().invoke(args);
    }

    /**
     * 校验入参；非法抛 {@link IllegalArgumentException}（工具名 + 全部错误），合法静默返回。
     *
     * <p>包级私有——同包的测试可以直接调，不必构造一整个工具再 {@link #invoke}。
     *
     * @param toolName 工具名（错误消息用）
     * @param schema   参数 JSON Schema；null 表示不校验
     * @param args     调用参数（null 视为空 Map）
     */
    private void validate(String toolName, JsonNode schema, Map<String, Object> args) {
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

    /**
     * 当前已注册工具数。
     */
    public int size() {
        return tools.size();
    }
}