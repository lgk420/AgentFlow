package com.agentflow.api;

import java.util.List;
import java.util.Map;

import com.agentflow.core.dsl.GraphParser;
import com.agentflow.core.dsl.GraphValidator;
import com.agentflow.core.model.WorkflowDefinition;
import com.agentflow.core.store.WorkflowStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作流定义 API（T1.5）。
 *
 * <p>POST body 用原始 JSON 字符串而非直接绑定 WorkflowDefinition——统一走 {@link GraphParser}
 * （节点 id 从键填充、解析错误统一为 WorkflowParseException），再经 {@link GraphValidator} 校验。
 * 校验失败返回 400 + {@code {"errors": [...]}}。
 */
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowApi {

    private final GraphParser parser;
    private final GraphValidator validator;
    private final WorkflowStore store;

    public WorkflowApi(GraphParser parser, GraphValidator validator, WorkflowStore store) {
        this.parser = parser;
        this.validator = validator;
        this.store = store;
    }

    /**
     * 提交工作流：解析 → 校验 → 入库。成功 201 + 存储的工作流；校验失败 400 + 错误列表。
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody String json) {
        WorkflowDefinition wf = parser.parse(json);
        List<String> errors = validator.validate(wf);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("errors", errors));
        }
        store.save(wf);
        return ResponseEntity.status(HttpStatus.CREATED).body(wf);
    }

    /**
     * 按 id 查工作流；不带 version 返回最新版本，带 {@code ?version=N} 返回指定版本；不存在 404。
     */
    @GetMapping("/{id}")
    public ResponseEntity<WorkflowDefinition> get(
            @PathVariable String id,
            @RequestParam(required = false) Integer version) {
        WorkflowDefinition wf = (version == null)
                ? store.findById(id)
                : store.findByIdAndVersion(id, version);
        if (wf == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(wf);
    }

    /**
     * 列某 id 的全部版本号。
     */
    @GetMapping("/{id}/versions")
    public ResponseEntity<Map<String, Object>> versions(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("id", id, "versions", store.findVersions(id)));
    }
}
