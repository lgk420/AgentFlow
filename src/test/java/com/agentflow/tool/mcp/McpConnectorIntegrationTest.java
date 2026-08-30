package com.agentflow.tool.mcp;

import java.util.List;
import java.util.Map;

import com.agentflow.tool.ToolRegistry;
import com.agentflow.tool.ToolSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5.5 McpConnector + McpToolAdapter 集成测试——连本地 demo stdio server（McpDemoServerMain），
 * 发现 calc 工具 → 包装注册进注册中心 → 真调用（可存可查可算）。
 */
class McpConnectorIntegrationTest {

    private McpConnector connector;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        connector = McpConnector.connect(demoServerConfig());
        registry = new ToolRegistry(new ToolSchemaValidator(new ObjectMapper()));
        McpToolAdapter adapter = new McpToolAdapter(connector, new ObjectMapper());
        for (McpSchema.Tool tool : connector.listTools()) {
            registry.register(adapter.adapt(tool));
        }
    }

    @AfterEach
    void tearDown() {
        connector.close();
    }

    @Test
    void demoServer_toolDiscoveredAndRegistered() {
        // 前缀 + 远端名：demo_calc
        assertThat(registry.contains("demo_calc")).isTrue();
        assertThat(registry.getAll()).anyMatch(d -> d.getDescription().contains("两数运算"));
    }

    @Test
    void registeredMcpTool_invokedThroughRegistry() {
        // schema 复用：入参校验通过（op 枚举 + a/b 数字），invoker 真调远端
        Object result = registry.invoke("demo_calc", Map.of("op", "mul", "a", 6, "b", 7));
        assertThat(result).isEqualTo("42.0");
    }

    @Test
    void schemaValidation_blocksBadArgs() {
        // op 不在枚举 → T5.3 校验拦截，不真调远端
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> registry.invoke("demo_calc", Map.of("op", "div", "a", 6, "b", 2))))
                .hasMessageContaining("op");
    }

    static McpProperties.Server demoServerConfig() {
        McpProperties.Server server = new McpProperties.Server();
        server.setName("demo-calc");
        server.setCommand("java");
        server.setArgs(List.of("-cp", System.getProperty("java.class.path"),
                "com.agentflow.tool.mcp.demo.McpDemoServerMain"));
        server.setPrefix("demo_");
        return server;
    }
}
