package com.agentflow.ability.tool;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 远程工具执行器（T5.6，T5.1 "远程" seam 落地）——invoke 时把参数 POST 到注册时给的 url，
 * 解析响应。工具的代码和数据跑在回调地址那端（同 MCP HTTP 原理，QA 62）。
 *
 * <p>响应解析：JSON 对象/数组 → Map/List；非 JSON → 原样字符串。
 * HTTP ≥400 → 抛 {@link IllegalStateException}（调用方按节点失败处理）。
 */
public class RemoteToolInvoker implements ToolInvoker {

    /**
     * 单次回调超时（秒），与 {@link ToolDescriptor#DEFAULT_TIMEOUT_MS} 一致。
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String url;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public RemoteToolInvoker(String url) {
        this(url, new ObjectMapper());
    }

    public RemoteToolInvoker(String url, ObjectMapper mapper) {
        this.url = url;
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.mapper = mapper;
    }

    @Override
    public Object invoke(Map<String, Object> args) {
        try {
            String body = mapper.writeValueAsString(args == null ? Map.of() : args);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(TIMEOUT)
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException(
                        "远程工具调用失败: " + url + " HTTP " + response.statusCode() + ": " + response.body());
            }
            return parseBody(response.body());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("远程工具调用失败: " + url + "：" + e.getMessage(), e);
        }
    }

    /**
     * JSON 对象/数组 → Map/List；否则原样字符串。
     */
    private Object parseBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return mapper.readValue(trimmed, Object.class);
            } catch (IOException ignored) {
                // 不是合法 JSON，落到字符串
            }
        }
        return trimmed;
    }
}
