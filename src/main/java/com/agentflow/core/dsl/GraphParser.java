package com.agentflow.core.dsl;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.agentflow.core.model.WorkflowDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.springframework.stereotype.Component;

/**
 * DSL 解析器：JSON → {@link WorkflowDefinition}。
 *
 * <p>字段绑定靠模型类上的 Jackson 注解完成；本类补两件 Jackson 不做的事：
 * ① 把 nodes 的键填进每个节点的 {@code id}（键即节点 id，见 DSL使用说明 3.1）；
 * ② 把底层解析异常包装成面向用户的 {@link WorkflowParseException}（见 DSL使用说明 7 常见错误）。
 *
 * <p>未知字段策略：生产环境 application.yml 已配 {@code fail-on-unknown-properties: true}，
 * 顶层 / 边字段拼错在解析期即报错（GraphValidator 仍负责边类型白名单等结构校验）。
 */
@Component
public class GraphParser {

    private final ObjectMapper mapper;

    public GraphParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 解析 DSL JSON 为工作流定义；任何解析失败抛 {@link WorkflowParseException}。
     */
    public WorkflowDefinition parse(String json) {
        if (json == null || json.isBlank()) {
            throw new WorkflowParseException("JSON 不能为空");
        }
        try {
            WorkflowDefinition wf = mapper.readValue(json, WorkflowDefinition.class);
            fillNodeIds(wf);
            return wf;
        } catch (JsonProcessingException e) {
            throw wrap(e);
        }
    }

    /**
     * 从已解析的 JsonNode 树解析（供上层复用 tree 时使用，行为与 {@link #parse(String)} 一致）。
     */
    public WorkflowDefinition parse(JsonNode node) {
        try {
            WorkflowDefinition wf = mapper.treeToValue(node, WorkflowDefinition.class);
            fillNodeIds(wf);
            return wf;
        } catch (JsonProcessingException e) {
            throw wrap(e);
        }
    }

    /**
     * nodes 键即节点 id：把键填进每个节点的 id（JSON 内层若也写了 id，以键为准覆盖）。
     */
    private static void fillNodeIds(WorkflowDefinition wf) {
        wf.getNodes().forEach((id, node) -> node.setId(id));
    }

    private WorkflowParseException wrap(JsonProcessingException e) {
        if (e instanceof InvalidFormatException ife
                && ife.getTargetType() != null && ife.getTargetType().isEnum()) {
            return new WorkflowParseException(
                    "未知的 type：" + ife.getValue() + "（期望 " + enumNames(ife.getTargetType()) + "）");
        }
        if (e instanceof UnrecognizedPropertyException upe) {
            return new WorkflowParseException("未知字段：" + upe.getPropertyName());
        }
        return new WorkflowParseException("JSON 解析失败：" + e.getOriginalMessage());
    }

    private static String enumNames(Class<?> enumType) {
        return Stream.of(enumType.getEnumConstants())
                .map(c -> ((Enum<?>) c).name())
                .collect(Collectors.joining("/"));
    }
}
