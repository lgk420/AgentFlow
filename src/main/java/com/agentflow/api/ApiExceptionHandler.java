package com.agentflow.api;

import java.util.Map;

import com.agentflow.core.dsl.WorkflowParseException;
import com.agentflow.tool.ToolConflictException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * API 统一异常处理：
 * <ul>
 *   <li>DSL 解析失败 / 工具注册非法参数 → 400 + {@code {"message": ...}}；</li>
 *   <li>工具重名注册 → 409。</li>
 * </ul>
 *
 * <p>解析错误（JSON 语法 / 未知 type / 未知字段）在 {@link GraphParser} 里已包装成面向用户的
 * {@link WorkflowParseException}；工具注册校验抛 {@link IllegalArgumentException}——这里统一转 HTTP 响应，
 * 避免 Controller 里散落 try/catch。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(WorkflowParseException.class)
    public ResponseEntity<Map<String, String>> handleParseError(WorkflowParseException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(ToolConflictException.class)
    public ResponseEntity<Map<String, String>> handleToolConflict(ToolConflictException e) {
        return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
    }
}
