package com.agentflow.demo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import com.agentflow.ability.tool.mcp.McpConnector;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.ConsoleAppender;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * 极简 MCP stdio server（T5.5 demo）——扮演"外部系统"，暴露一个 {@code calc} 工具
 * （add / sub / mul 两数运算），供 {@code McpConnector} 连接注册，验证 MCP 全链路。
 *
 * <p>启动方式（与配置 {@code agentflow.mcp.servers} 的 command/args 对齐）：
 * {@code java -cp <classpath> com.agentflow.demo.McpDemoServerMain}。
 *
 * <p><b>MCP stdio 协议要求日志走 stderr</b>：客户端逐行读 server 的 stdout 当 JSON-RPC，
 * 日志打到 stdout 会污染协议流（实测踩坑）。main 里先把 root logger 的 ConsoleAppender 指到 System.err。
 */
public final class McpDemoServerMain {

    private McpDemoServerMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        routeLogsToStderr();
        McpJsonMapper mapper = McpConnector.loadJsonMapper();
        StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(mapper);

        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo("agentflow-demo-mcp", "1.0.0")
                .tools(calcTool())
                .build();

        // stdio server 常驻：阻塞主线程，等 stdin EOF / 进程被杀
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.closeGracefully();
            latch.countDown();
        }));
        latch.await();
    }

    /**
     * calc 工具：op ∈ {add, sub, mul}，a、b 为数字。
     */
    private static SyncToolSpecification calcTool() {
        // JsonSchema 是 record（type, properties, required, additionalProperties, defs, definitions），无 builder
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "op", Map.of("type", "string", "enum", List.of("add", "sub", "mul")),
                        "a", Map.of("type", "number"),
                        "b", Map.of("type", "number")),
                List.of("op", "a", "b"),
                null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("calc")
                .description("两数运算：add 加 / sub 减 / mul 乘")
                .inputSchema(schema)
                .build();

        // 0.15.0 的 SyncToolSpecification 回调直接传参数 Map（非 CallToolRequest）
        return new SyncToolSpecification(tool, (exchange, arguments) -> {
            double a = num(arguments.get("a"));
            double b = num(arguments.get("b"));
            double result = switch (String.valueOf(arguments.get("op"))) {
                case "sub" -> a - b;
                case "mul" -> a * b;
                default -> a + b;
            };
            return new CallToolResult(List.of(new TextContent(String.valueOf(result))), false, null, null);
        });
    }

    private static double num(Object value) {
        return value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString());
    }

    /**
     * 把 root logger 的日志改写到 stderr（MCP stdio 协议约束，见类注释）。
     */
    private static void routeLogsToStderr() {
        LoggerContext context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAndStopAllAppenders();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        encoder.start();

        ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ConsoleAppender<>();
        appender.setContext(context);
        appender.setTarget("System.err");
        appender.setEncoder(encoder);
        appender.start();

        root.addAppender(appender);
    }
}
