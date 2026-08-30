package com.agentflow.tool;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T5.6 RemoteToolInvoker 单元测试——回调 URL 执行（代码跑在回调端）。
 *
 * <p>用 JDK {@link HttpServer} 起本地端点回显收到的参数，验证：invoke 真 POST 参数过去、
 * JSON 响应解析成 Map、非 JSON 响应原样字符串、HTTP ≥400 抛明确错误。
 */
class RemoteToolInvokerTest {

    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private RemoteToolInvoker invokerAt(String path) {
        return new RemoteToolInvoker("http://localhost:" + server.getAddress().getPort() + path);
    }

    @Test
    void invoke_postsArgsToUrl_andParsesJsonResponse() {
        server.createContext("/echo", exchange -> respond(exchange, readBody(exchange))); // 原样回显
        Object result = invokerAt("/echo").invoke(Map.of("op", "add", "a", 2, "b", 3));

        assertThat(result).isEqualTo(Map.of("op", "add", "a", 2, "b", 3)); // 参数 POST 到远端并被解析回
    }

    @Test
    void invoke_nonJsonResponse_returnsRawString() {
        server.createContext("/text", exchange -> respond(exchange, "pong"));
        Object result = invokerAt("/text").invoke(Map.of());
        assertThat(result).isEqualTo("pong");
    }

    @Test
    void invoke_httpError_throwsWithStatus() {
        server.createContext("/fail", exchange -> respond(exchange, 500, "boom"));
        assertThatThrownBy(() -> invokerAt("/fail").invoke(Map.of("a", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500");
    }

    private static String readBody(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, body);
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
