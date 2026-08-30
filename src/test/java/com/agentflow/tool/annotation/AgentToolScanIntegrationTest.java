package com.agentflow.tool.annotation;

import java.util.Map;

import com.agentflow.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5.2 端到端：真实 Spring 上下文启动后，ApplicationRunner 把测试 Bean 的 @AgentTool 方法注册进注册中心
 * （对应验收"一个 @AgentTool 方法启动后出现在 GET /tools"）。
 */
@SpringBootTest
class AgentToolScanIntegrationTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @TestConfiguration
    static class Config {
        @Bean
        DemoAgentTools demoAgentTools() {
            return new DemoAgentTools();
        }
    }

    public static class DemoAgentTools {
        @AgentTool(name = "add", description = "两数相加")
        public int add(int a, int b) {
            return a + b;
        }
    }

    @Test
    void agentToolRegisteredAfterStartup() {
        assertThat(toolRegistry.contains("add")).isTrue();
        assertThat(toolRegistry.invoke("add", Map.of("a", 2, "b", 3))).isEqualTo(5);
    }
}
