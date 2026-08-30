package com.agentflow.api;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1.5 Workflow API 全链路测试（Spring 上下文 + Testcontainers redis + MockMvc）。
 *
 * <p>覆盖：POST 好图 → 201；POST 坏图 → 400 + errors；POST 坏 JSON → 400 + message；
 * GET 最新版本 → 200；GET 缺失 → 404；GET versions → 升序列版本。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WorkflowApiTest {

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

    private String readResource(String resource) throws Exception {
        return new String(getClass().getResourceAsStream(resource).readAllBytes(), StandardCharsets.UTF_8);
    }

    private String withIdAndVersion(String resource, String id, int version) throws Exception {
        ObjectNode node = (ObjectNode) new ObjectMapper().readTree(readResource(resource));
        node.put("id", id);
        node.put("version", version);
        return new ObjectMapper().writeValueAsString(node);
    }

    @Test
    void createValidWorkflow_thenGet() throws Exception {
        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readResource("/testdata/workflows/legacy-fitness-coach/fitness-coach.json")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("fitness-coach"))
                .andExpect(jsonPath("$.nodes.parse.type").value("LLM"));

        mockMvc.perform(get("/api/v1/workflows/fitness-coach"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("fitness-coach"))
                .andExpect(jsonPath("$.nodes.report.type").value("AGENTIC_LOOP"));
    }

    @Test
    void createInvalidWorkflow_returns400WithErrors() throws Exception {
        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readResource("/testdata/workflows/validation/cycle.json")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0]").exists());
    }

    @Test
    void createMalformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void getMissingWorkflow_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/workflows/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void versions_endpoint_listsAscending() throws Exception {
        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withIdAndVersion("/testdata/workflows/legacy-fitness-coach/fitness-coach.json", "wf-versions", 1)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withIdAndVersion("/testdata/workflows/legacy-fitness-coach/fitness-coach.json", "wf-versions", 2)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/workflows/wf-versions/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions", containsInRelativeOrder(1, 2)));
    }

    @Test
    void getByExplicitVersion_viaQueryParam() throws Exception {
        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withIdAndVersion("/testdata/workflows/legacy-fitness-coach/fitness-coach.json", "wf-vers", 1)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withIdAndVersion("/testdata/workflows/legacy-fitness-coach/fitness-coach.json", "wf-vers", 2)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/workflows/wf-vers?version=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(get("/api/v1/workflows/wf-vers?version=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }
}
