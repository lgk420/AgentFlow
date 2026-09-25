package com.agentflow.ability.tool.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.agentflow.ability.tool.ToolDescriptor;
import com.agentflow.ability.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * MCP 工具同步器（T5.5，注册中心来源③）——启动时遍历配置的 MCP 服务器，
 * 连接 → 发现工具 → 包装成 {@link ToolDescriptor} 注册进注册中心。
 *
 * <p><b>失败不阻断</b>（T5.5 决策）：某台 server 连接/注册失败 → 打 warning 跳过，
 * 不阻断应用启动（工具可热插拔，少几个不致命）。
 */
@Component
public class McpToolSynchronizer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(McpToolSynchronizer.class);

    private final McpProperties properties;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper mapper;

    public McpToolSynchronizer(McpProperties properties, ToolRegistry toolRegistry, ObjectMapper mapper) {
        this.properties = properties;
        this.toolRegistry = toolRegistry;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (McpProperties.Server server : properties.getServers()) {
            try (McpConnector connector = McpConnector.connect(server)) {
                McpToolAdapter adapter = new McpToolAdapter(connector, mapper);
                List<ToolDescriptor> descriptors = new ArrayList<>();
                for (McpSchema.Tool tool : connector.listTools()) {
                    descriptors.add(adapter.adapt(tool));
                }
                for (ToolDescriptor d : descriptors) {
                    toolRegistry.register(d);
                }
                String names = descriptors.stream().map(ToolDescriptor::getName).collect(Collectors.joining(", "));
                log.info("MCP 服务器 [{}] 注册 {} 个工具: {}", server.getName(), descriptors.size(), names);
            } catch (Exception e) {
                log.warn("MCP 服务器 [{}] 连接/注册失败，跳过（失败不阻断）: {}", server.getName(), e.getMessage());
            }
        }
    }
}
