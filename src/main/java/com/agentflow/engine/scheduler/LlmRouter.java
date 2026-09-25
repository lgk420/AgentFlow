package com.agentflow.engine.scheduler;

import java.util.List;
import java.util.Map;

import com.agentflow.ability.llm.LlmGateway;
import com.agentflow.ability.llm.LlmStructuredChat;
import com.agentflow.engine.model.definition.EdgeDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * LLM 动态路由（T4.4，LLM_DYNAMIC 边）——上游节点完成后，收集其 LLM_DYNAMIC 出边
 * （各带 label/description 作为候选），让模型基于上游输出选一条，返回目标节点 id。
 *
 * <p>流程：候选边 → 拼 prompt（候选 to/label/描述 + 上游输出）→ 结构化输出 {@code {nextNode}}
 * （{@link LlmStructuredChat}，QA 47）→ 校验落在候选集；非法（模型幻觉）→ <b>fallback 走声明顺序第一条</b>。
 *
 * <p>调用方（{@link com.agentflow.engine.scheduler.WorkflowExecutor}）拿到目标后：选中边触发、其余候选边传播死分支
 * （死分支机制 T2.4 复用）。
 */
@Component
public class LlmRouter {

    private final LlmGateway llmGateway;
    private final ObjectMapper mapper;

    public LlmRouter(LlmGateway llmGateway, ObjectMapper mapper) {
        this.llmGateway = llmGateway;
        this.mapper = mapper;
    }

    /**
     * 从候选 LLM_DYNAMIC 边选一条，返回其目标节点 id。
     *
     * @param candidates     上游节点的 LLM_DYNAMIC 出边（至少一条）
     * @param upstreamOutput 上游节点输出（路由决策的上下文，可空）
     * @return 选中边的目标节点 id；模型返回非法值时取声明顺序第一条
     */
    public String route(List<EdgeDefinition> candidates, Object upstreamOutput) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("LLM_DYNAMIC 路由无候选边");
        }
        Map<String, Object> parsed = LlmStructuredChat.chatStructured(llmGateway, mapper,
                buildPrompt(candidates, upstreamOutput), buildSchema(candidates));
        Object nextNode = parsed.get("nextNode");
        if (nextNode != null && candidates.stream().anyMatch(c -> c.getTo().equals(nextNode.toString()))) {
            return nextNode.toString();
        }
        return candidates.get(0).getTo(); // fallback：非法 nextNode → 第一条
    }

    /**
     * 输出 schema：{@code {nextNode: string, enum=候选目标 id 列表}}，约束模型只能从候选取。
     */
    private JsonNode buildSchema(List<EdgeDefinition> candidates) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode nextNode = root.putObject("properties").putObject("nextNode");
        nextNode.put("type", "string");
        ArrayNode enumArr = nextNode.putArray("enum");
        for (EdgeDefinition c : candidates) {
            enumArr.add(c.getTo());
        }
        root.putArray("required").add("nextNode");
        return root;
    }

    private String buildPrompt(List<EdgeDefinition> candidates, Object upstreamOutput) {
        StringBuilder sb = new StringBuilder("你是流程路由员，根据上游输出决定下一步走哪个分支。可选分支：\n");
        for (EdgeDefinition c : candidates) {
            sb.append("- ").append(c.getTo());
            if (c.getLabel() != null && !c.getLabel().isBlank()) {
                sb.append("：").append(c.getLabel());
            }
            if (c.getDescription() != null && !c.getDescription().isBlank()) {
                sb.append("（").append(c.getDescription()).append("）");
            }
            sb.append('\n');
        }
        if (upstreamOutput != null) {
            sb.append("上游输出：").append(upstreamOutput).append('\n');
        }
        sb.append("只输出 {\"nextNode\": \"<分支id>\"}，分支id 必须是上面列出的候选之一。");
        return sb.toString();
    }
}
