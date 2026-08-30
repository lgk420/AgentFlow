package com.agentflow.tool.mcp;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MCP 服务器配置（T5.5，来源③）——绑定 {@code agentflow.mcp.servers}。
 *
 * <p>字段：name（服务器名，日志用）、command + args（stdio transport 的启动命令）、
 * prefix（工具名前缀，防与内置 {@code @AgentTool} 重名导致启动冲突）。
 */
@Component
@ConfigurationProperties(prefix = "agentflow.mcp")
public class McpProperties {

    /**
     * 配置的 MCP 服务器列表。
     */
    private List<Server> servers = new ArrayList<>();

    public List<Server> getServers() {
        return servers;
    }

    public void setServers(List<Server> servers) {
        this.servers = servers;
    }

    public static class Server {

        /**
         * 服务器名（日志 / 前缀隔离标识）。
         */
        private String name;

        /**
         * stdio transport：启动 server 的可执行命令（如 {@code java}）。
         */
        private String command;

        /**
         * stdio transport：命令参数（如 {@code [-cp, <classpath>, <MainClass>]}）。
         */
        private List<String> args = new ArrayList<>();

        /**
         * 工具名前缀，缺省空（不冲突时裸名）。
         */
        private String prefix = "";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args;
        }

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }
    }
}
