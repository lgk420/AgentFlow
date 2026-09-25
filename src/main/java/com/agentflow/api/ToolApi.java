package com.agentflow.api;

import java.util.List;

import com.agentflow.ability.tool.RemoteToolInvoker;
import com.agentflow.ability.tool.ToolConflictException;
import com.agentflow.ability.tool.ToolDescriptor;
import com.agentflow.ability.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具 API（T5.2 起步，T5.6 补全 register/delete）——动态注册/注销（来源②编程式）。
 *
 * <p>不直接序列化 {@link ToolDescriptor}：其 {@code invoker} 是函数式接口、Jackson 无法表达，
 * 故用不含 invoker 的 {@link ToolSummary} 输出。
 *
 * <p>注册语义：POST /api/v1/tools，body = name/description/parameters/url；
 * 重名 → {@link ToolConflictException} → 409；非法（缺 name/url）→ {@link IllegalArgumentException} → 400
 * （由 {@link ApiExceptionHandler} 映射）。注销：DELETE /api/v1/tools/{name}，不存在 → 404。
 */
@RestController
@RequestMapping("/api/v1/tools")
public class ToolApi {

    private final ToolRegistry toolRegistry;

    public ToolApi(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 列出注册中心全部工具（不含执行器）。
     */
    @GetMapping
    public List<ToolSummary> list() {
        return toolRegistry.getAll().stream()
                .map(d -> new ToolSummary(d.getName(), d.getDescription(), d.getParameters(), d.getTimeoutMs()))
                .toList();
    }

    /**
     * 动态注册工具（T5.6，来源②）：name/description/parameters(schema)/url。
     * 回调 url 由 {@link RemoteToolInvoker} 执行（代码跑在回调端）。
     */
    @PostMapping
    public ResponseEntity<ToolSummary> register(@RequestBody RegisterToolRequest request) {
        String name = request.getName();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        if (request.getUrl() == null || request.getUrl().isBlank()) {
            throw new IllegalArgumentException("工具缺少 url（回调地址）");
        }
        ToolDescriptor descriptor = new ToolDescriptor(name, request.getDescription(), request.getParameters(),
                new RemoteToolInvoker(request.getUrl()));
        toolRegistry.register(descriptor);
        return ResponseEntity.status(HttpStatus.CREATED).body(toSummary(descriptor));
    }

    /**
     * 注销工具（T5.6）：不存在 → 404，存在 → 204。
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        if (!toolRegistry.remove(name)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    private static ToolSummary toSummary(ToolDescriptor d) {
        return new ToolSummary(d.getName(), d.getDescription(), d.getParameters(), d.getTimeoutMs());
    }

    /**
     * 工具对外摘要——name / description / 参数 schema / 超时。
     */
    public static class ToolSummary {

        private final String name;
        private final String description;
        private final JsonNode parameters;
        private final long timeoutMs;

        public ToolSummary(String name, String description, JsonNode parameters, long timeoutMs) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
            this.timeoutMs = timeoutMs;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public JsonNode getParameters() {
            return parameters;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }
    }
}
