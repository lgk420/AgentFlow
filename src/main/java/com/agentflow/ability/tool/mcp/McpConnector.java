package com.agentflow.ability.tool.mcp;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * MCP 连接器（T5.5，来源③）——一台 MCP Server 的客户端封装（官方 Java SDK 薄封装，QA 58）。
 *
 * <p>生命周期：按配置起 stdio transport（{@code command + args} 拉起本地 server 进程）→
 * {@code initialize} 握手 → {@link #listTools()} 发现 → {@link #callTool} 调用 → {@link #close}。
 * 工具发现 / 调用的产物（{@link McpSchema.Tool} / {@link CallToolResult}）是裸协议数据，
 * 由 {@link McpToolAdapter} 包装成 {@link com.agentflow.ability.tool.ToolDescriptor} 注册进注册中心。
 *
 * <p>调用结果解析：优先 {@code structuredContent()}（结构化 Map/List），否则取文本内容拼串。
 */
public class McpConnector implements AutoCloseable {

    private final McpSyncClientHolder holder;
    private final String prefix;

    private McpConnector(McpSyncClientHolder holder, String prefix) {
        this.holder = holder;
        this.prefix = prefix;
    }

    /**
     * 连接配置的 MCP Server（stdio），完成 initialize 握手。
     *
     * @param config 服务器配置（command + args + prefix）
     * @return 已就绪的连接器；连接/握手失败抛异常（由调用方决定是否阻断）
     */
    public static McpConnector connect(McpProperties.Server config) {
        McpJsonMapper mapper = loadJsonMapper();
        ServerParameters params = ServerParameters.builder(config.getCommand())
                .args(resolveClasspath(config.getArgs()))
                .build();
        StdioClientTransport transport = new StdioClientTransport(params, mapper);
        var client = McpClient.sync(transport).build();
        client.initialize();
        return new McpConnector(new McpSyncClientHolder(client), config.getPrefix());
    }

    /**
     * 解析 args 里的 {@code ${java.class.path}} 占位符 → 当前 JVM classpath
     * （配置里可写 {@code [-cp, ${java.class.path}, <MainClass>]}，IDE / mvn / 测试通用）。
     */
    private static List<String> resolveClasspath(List<String> args) {
        return args.stream().map(arg -> arg.equals("${java.class.path}")
                ? System.getProperty("java.class.path") : arg).toList();
    }

    /**
     * 本服务器的工具名前缀（适配时拼到远端工具名前，防与内置工具重名）。
     */
    public String prefix() {
        return prefix;
    }

    /**
     * 远端工具列表（name / description / inputSchema）。
     */
    public List<McpSchema.Tool> listTools() {
        return holder.client().listTools().tools();
    }

    /**
     * 调用远端工具，返回解析后的结果对象（Map / List / 字符串）。
     *
     * @throws IllegalStateException 工具执行报错（isError=true）或非 JSON 文本解析失败
     */
    public Object callTool(String toolName, Map<String, Object> args) {
        CallToolResult result = holder.client().callTool(new CallToolRequest(toolName, args));
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("MCP 工具调用失败: " + toolName + "：" + contentText(result));
        }
        Object structured = result.structuredContent();
        if (structured != null) {
            return structured;
        }
        return contentText(result);
    }

    @Override
    public void close() {
        holder.client().closeGracefully();
    }

    private static String contentText(CallToolResult result) {
        if (result.content() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content c : result.content()) {
            if (c instanceof McpSchema.TextContent text) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(text.text());
            }
        }
        return sb.toString();
    }

    /**
     * McpJsonMapper 由 mcp-json-jackson3 模块经 SPI 提供（io.modelcontextprotocol.json.McpJsonMapperSupplier）。
     */
    public static McpJsonMapper loadJsonMapper() {
        return ServiceLoader.load(McpJsonMapperSupplier.class).findFirst().orElseThrow()
                .get();
    }

    /**
     * 持有 McpSyncClient 的小包装，避免把 SDK 类型泄漏成公开字段。
     */
    private record McpSyncClientHolder(io.modelcontextprotocol.client.McpSyncClient client) {
    }
}
