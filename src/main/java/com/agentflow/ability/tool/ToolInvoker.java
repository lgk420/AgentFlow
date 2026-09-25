package com.agentflow.ability.tool;

import java.util.Map;

/**
 * 工具执行器（T5.1）——工具怎么真正干活。
 *
 * <p>入参统一 {@code Map<String, Object>}（对应 DSL TOOL 节点 {@code inputs} 对象与 LLM tool-call 的 arguments），
 * 返回任意对象（Map / List / 字符串 / 数字），由上层决定如何序列化进 state / trace。
 * 方法引用、MCP 远程工具都适配成它——这是注册中心"不关心工具从哪来"的 seam。
 */
@FunctionalInterface
public interface ToolInvoker {

    /**
     * 执行工具。
     *
     * @param args 工具入参（JSON 对象形态），已通过 ToolSchemaValidator 校验
     * @return 工具输出，任意 JSON 可序列化类型
     */
    Object invoke(Map<String, Object> args);
}