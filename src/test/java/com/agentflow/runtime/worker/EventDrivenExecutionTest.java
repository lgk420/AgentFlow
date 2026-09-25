package com.agentflow.runtime.worker;

import com.agentflow.ability.tool.annotation.AgentTool;
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
 * T7.3 验收：事件驱动执行端到端——完整工作流经 run-worker / node-worker 消费事件执行到 SUCCEEDED。
 *
 * <p>用 t2/parallel（TOOL + STATIC，桩工具）验证：POST /runs 发 RunStarted → run-worker 发 NodeReady(start)
 * → node-worker 逐个执行节点 → 算就绪派发下游 → 到 END 完成运行。执行路径全部经事件（workers 默认开启）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EventDrivenExecutionTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // core.event-driven.enabled 缺省 true → workers 启动，事件驱动执行
    }

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class StubToolsConfig {

        @Bean
        ParallelQueryTools parallelQueryTools() {
            return new ParallelQueryTools();
        }
    }

    /** t2/parallel 的 TOOL 节点引用的桩工具（同 RunApiTest）。 */
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

    @Test
    void parallelWorkflow_completesThroughEvents() throws Exception {
        // 存 t2/parallel（TOOL + STATIC）
        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readResource("/testdata/workflows/t2/parallel.json")))
                .andExpect(status().isCreated());

        // POST run：RunStarted → 事件驱动执行 → 轮询到终态返回 SUCCEEDED（执行路径全部经事件）
        MvcResult result = mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "workflowId": "t2-parallel", "inputs": { "userMessage": "hi" } }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.runId").exists())
                .andReturn();

        String runId = JsonPath.read(result.getResponse().getContentAsString(), "$.runId");

        // 节点输出齐全（经事件执行的真实证据）
        mockMvc.perform(get("/api/v1/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.nodeOutputs.tool1.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.nodeOutputs.tool2.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.nodeOutputs.end.status").value("SUCCEEDED"));
    }

    private String readResource(String resource) throws Exception {
        return new String(getClass().getResourceAsStream(resource).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
