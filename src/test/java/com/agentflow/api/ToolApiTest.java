package com.agentflow.api;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.agentflow.api.ToolApi.ToolSummary;
import com.agentflow.ability.tool.ToolConflictException;
import com.agentflow.ability.tool.ToolDescriptor;
import com.agentflow.ability.tool.ToolRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T5.2 工具列表 + T5.6 动态注册/注销接口验收。
 *
 * <p>覆盖：列表；注册 → 201 且出现在列表；重名 → {@link ToolConflictException}；缺 name/url → 400 语义异常；
 * 删除 → 204 且消失；删除不存在 → 404；注册带回调 url 的工具能被真调用（参数打到回调端）。
 */
class ToolApiTest {

    private ToolRegistry registry;
    private ToolApi api;
    private HttpServer callbackServer;

    @BeforeEach
    void setUp() throws IOException {
        registry = new ToolRegistry(null);
        api = new ToolApi(registry);
        callbackServer = HttpServer.create(new InetSocketAddress(0), 0);
        callbackServer.start();
    }

    @AfterEach
    void tearDown() {
        callbackServer.stop(0);
    }

    private RegisterToolRequest request(String name, String url) {
        RegisterToolRequest r = new RegisterToolRequest();
        r.setName(name);
        r.setDescription("远程工具 " + name);
        r.setUrl(url);
        return r;
    }

    private String callbackUrl(String path) {
        return "http://localhost:" + callbackServer.getAddress().getPort() + path;
    }

    // ---------- T5.2 列表 ----------

    @Test
    void list_returnsRegisteredTools() {
        registry.register(new ToolDescriptor("add", "加法", null, args -> 1));

        List<ToolSummary> summaries = api.list();

        assertThat(summaries).hasSize(1);
        ToolSummary s = summaries.get(0);
        assertThat(s.getName()).isEqualTo("add");
        assertThat(s.getDescription()).isEqualTo("加法");
        assertThat(s.getParameters()).isNull();
        assertThat(s.getTimeoutMs()).isEqualTo(ToolDescriptor.DEFAULT_TIMEOUT_MS);
    }

    @Test
    void list_emptyRegistry_returnsEmpty() {
        assertThat(api.list()).isEmpty();
    }

    // ---------- T5.6 注册 ----------

    @Test
    void register_returnsCreated_andAppearsInList() {
        ResponseEntity<ToolSummary> response = api.register(request("weather", callbackUrl("/hook")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getName()).isEqualTo("weather");
        assertThat(api.list()).extracting(ToolSummary::getName).contains("weather");
    }

    @Test
    void register_duplicateName_throwsConflict() {
        api.register(request("weather", callbackUrl("/hook")));
        assertThatThrownBy(() -> api.register(request("weather", callbackUrl("/hook2"))))
                .isInstanceOf(ToolConflictException.class);
    }

    @Test
    void register_missingUrl_throws() {
        assertThatThrownBy(() -> api.register(request("weather", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
    }

    @Test
    void register_blankName_throws() {
        assertThatThrownBy(() -> api.register(request("  ", callbackUrl("/hook"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工具名");
    }

    // ---------- T5.6 注销 ----------

    @Test
    void delete_existing_returns204_andRemoves() {
        api.register(request("weather", callbackUrl("/hook")));

        ResponseEntity<Void> response = api.delete("weather");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.list()).extracting(ToolSummary::getName).doesNotContain("weather");
    }

    @Test
    void delete_missing_returns404() {
        ResponseEntity<Void> response = api.delete("nope");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------- T5.6 注册后可真调用（回调 URL 执行） ----------

    @Test
    void registeredTool_canBeInvoked_throughCallbackUrl() throws Exception {
        callbackServer.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, 200, body); // 原样回显收到的参数
        });
        api.register(request("echo_tool", callbackUrl("/hook")));

        // 从注册中心取回注册的工具并真调用 → 参数 POST 到回调端、结果解析回来
        Object result = registry.invoke("echo_tool", Map.of("a", 2, "b", 3));

        assertThat(result).isEqualTo(Map.of("a", 2, "b", 3));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        exchange.close();
    }
}
