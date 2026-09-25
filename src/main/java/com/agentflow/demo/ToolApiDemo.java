package com.agentflow.demo;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.agentflow.api.RegisterToolRequest;
import com.agentflow.api.ToolApi;
import com.agentflow.api.ToolApi.ToolSummary;
import com.agentflow.ability.tool.ToolRegistry;
import com.agentflow.ability.tool.ToolSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * T5.6 独立 demo——动态注册/注销 + 回调 URL 执行，不依赖应用/Redis/Docker。
 *
 * <p>流程：本地起一个回显端点扮演"外部系统的工具"（代码跑在这端）→
 * {@link ToolApi#register} 注册 → 列表可见 → {@link ToolRegistry#invoke} 真调用（POST 参数到回调端）→
 * {@link ToolApi#delete} 注销 → 列表消失。
 *
 * <p>运行：{@code mvn -q compile && java -cp "target/classes;<依赖classpath>" com.agentflow.demo.ToolApiDemo}
 * （依赖 classpath 用 {@code mvn dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt} 生成）。
 */
public final class ToolApiDemo {

    private ToolApiDemo() {
    }

    public static void main(String[] args) throws Exception {
        // ① 本地回显端点 = "外部系统"：收到参数原样返回（代码和数据在这端，QA 62）
        HttpServer callback = HttpServer.create(new InetSocketAddress(0), 0);
        callback.createContext("/echo", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, 200, body);
        });
        callback.start();
        String url = "http://localhost:" + callback.getAddress().getPort() + "/echo";
        System.out.println("① 起本地回调端点扮演外部系统 → " + url);

        ToolRegistry registry = new ToolRegistry(new ToolSchemaValidator(new ObjectMapper()));
        ToolApi api = new ToolApi(registry);

        // ② 动态注册：name/description/schema + 回调 url
        RegisterToolRequest request = new RegisterToolRequest();
        request.setName("echo_tool");
        request.setDescription("回显传入的参数（远程工具）");
        request.setUrl(url);
        api.register(request);
        System.out.println("② POST 注册 echo_tool（回调 " + url + "）");
        System.out.println("   GET /tools → " + summarize(api.list()));

        // ③ 调用：经注册中心 → RemoteToolInvoker → POST 参数到回调端 → 解析返回
        Object result = registry.invoke("echo_tool", Map.of("a", 2, "b", 3));
        System.out.println("③ 调用 echo_tool(a=2,b=3) → " + result + "（代码在回调端跑）");

        // ④ 注销
        api.delete("echo_tool");
        System.out.println("④ DELETE echo_tool → 列表=" + summarize(api.list()));

        callback.stop(0);
    }

    private static String summarize(List<ToolSummary> summaries) {
        return summaries.isEmpty() ? "[]" : summaries.stream().map(ToolSummary::getName).toList().toString();
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
