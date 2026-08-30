package com.agentflow.tool.mcp;

import java.util.Map;

import com.agentflow.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP 工具适配器（T5.5）——把一条 MCP 远端工具 {@link McpSchema.Tool} 包装成
 * {@link ToolDescriptor} 注册进注册中心。
 *
 * <p>映射：
 * <ul>
 *   <li>name = 服务器前缀 + 远端工具名（前缀防与内置 {@code @AgentTool} 重名，见 {@link McpProperties.Server#prefix}）；</li>
 *   <li>description = 远端工具描述（原样）；</li>
 *   <li>parameters = 远端 inputSchema 直接转 {@link JsonNode}——<b>一套 schema 两个用途</b>
 *       （T5.3 入参校验 + LLM function 定义）在 MCP 工具上延续；</li>
 *   <li>invoker = 调 {@link McpConnector#callTool}（把参数透传给远端）。</li>
 * </ul>
 */
public class McpToolAdapter {

    private final McpConnector connector;
    private final ObjectMapper mapper;

    public McpToolAdapter(McpConnector connector, ObjectMapper mapper) {
        this.connector = connector;
        this.mapper = mapper;
    }

    /**
     * 远端工具 → 注册中心的 ToolDescriptor。
     */
    public ToolDescriptor adapt(McpSchema.Tool tool) {
        String name = connector.prefix() + tool.name();
        JsonNode parameters = mapper.valueToTree(tool.inputSchema());
        return new ToolDescriptor(name, tool.description(), parameters,
                (Map<String, Object> args) -> connector.callTool(tool.name(), args));
    }
}
