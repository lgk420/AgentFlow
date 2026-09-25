package com.agentflow.ability.tool;

/**
 * 工具重名注册异常（T5.1）——ToolRegistry.register 遇到已存在的 name 抛出。
 *
 * <p>运行时动态注册（T5.6）同 names 冲突也走这里；不改名、不静默覆盖，
 * 由调用方决定"替换"语义（先 remove 再 register）。
 */
public class ToolConflictException extends RuntimeException {

    public ToolConflictException(String name) {
        super("工具已注册，重名冲突: " + name);
    }
}