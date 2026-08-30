package com.agentflow.api;

import java.nio.charset.StandardCharsets;

import com.agentflow.tool.annotation.AgentTool;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T2.7 运行 API 验收测试（Spring 上下文 + Testcontainers redis + MockMvc）。
 *
 * <p>覆盖：存图 → 提交运行（同步，返回 SUCCEEDED）→ 查询状态；未知工作流 404；未知 run 404。
 * 用 t2/parallel（TOOL + STATIC，过校验且 P2 可跑）。
 *
 * <p>T5.4 起 TOOL 节点走真 {@link com.agentflow.core.exec.ToolNodeExecutor}，需在注册中心里
 * 有 query_a / query_b 两个工具（嵌套 {@link TestConfiguration} 提供 {@code @AgentTool} bean，
 * 启动时由 {@code AgentToolRegistrar} 扫描注册），否则 t2/parallel 的 TOOL 节点会因"工具不存在"FAILED。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RunApiTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class MockToolsConfig {
        @Bean
        ParallelQueryTools parallelQueryTools() {
            return new ParallelQueryTools();
        }
    }

    /**
     * t2/parallel 的 TOOL 节点引用的桩工具（T5.4 后走真执行器，注册中心需有这两个工具）。
     */
    public static class ParallelQueryTools {
        @AgentTool(name = "query_a", description = "桩工具 A")
        public String queryA() {
            return "query_a 桩输出";
        }

        @AgentTool(name = "query_b", description = "桩工具 B")
        public String queryB() {
            return "query_b 桩输出";
        }
    }

    private String readResource(String resource) throws Exception {
        return new String(getClass().getResourceAsStream(resource).readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void submitRun_thenQuerySucceeded() throws Exception {
        // 先存一张 P2 可执行的图（t2/parallel：TOOL + STATIC，能过校验）
        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readResource("/testdata/workflows/t2/parallel.json")))
                .andExpect(status().isCreated());

        String runBody = """
                { "workflowId": "t2-parallel", "inputs": { "userMessage": "hi" } }
                """;
        MvcResult result = mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.runId").exists())
                .andReturn();

        String runId = JsonPath.read(result.getResponse().getContentAsString(), "$.runId");

        mockMvc.perform(get("/api/v1/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.nodeOutputs.tool1.status").value("SUCCEEDED"));
    }

    @Test
    void submitRun_unknownWorkflow_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "workflowId": "nope", "inputs": {} }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void queryRun_missing_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/runs/does-not-exist"))
                .andExpect(status().isNotFound());
    }
}
