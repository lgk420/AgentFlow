package com.agentflow.ability.llm;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * LLM 结构化输出共享工具（T4.4 抽取，QA 47）——prompt 附 schema 指令 → {@link LlmGateway#chat} → Jackson 解析成 Map。
 *
 * <p>{@code LlmNodeExecutor}（LLM 节点 outputSchema）与 {@link com.agentflow.engine.scheduler.LlmRouter}
 * （LLM_DYNAMIC 路由返回 {@code {nextNode}}）共用同一套路，避免两处重复。
 * 用 {@code chat()} + 通用 JSON schema 而非 Spring AI Class 转换——任意 schema 通用（QA 47）。
 */
public final class LlmStructuredChat {

    private LlmStructuredChat() {
    }

    /**
     * 结构化输出：prompt 附 schema → 模型返回 JSON → 解析成 Map。
     *
     * <p>解析前经 {@link #stripCodeFence} 剥掉模型常见的 markdown 代码块围栏（真实链路踩坑，见 docs/bugs/01）；
     * prompt 也同步明确禁止代码块，双保险。
     *
     * @param gateway  LLM 网关
     * @param mapper   JSON 解析器
     * @param prompt   业务提示词（不含 schema 指令）
     * @param schema   输出结构（JSON Schema），拼进 prompt 约束模型
     * @return 解析后的对象
     * @throws IllegalStateException 模型返回的不是合法 JSON
     */
    public static Map<String, Object> chatStructured(LlmGateway gateway, ObjectMapper mapper,
                                                     String prompt, JsonNode schema) {
        String fullPrompt = prompt
                + "\n\n你必须只输出一个 JSON 对象，符合以下 schema。直接输出 JSON，"
                + "不要用 markdown 代码块（不要 ```json 围栏）：\n" + schema;
        String text = gateway.chat(null, fullPrompt);
        try {
            return mapper.readValue(stripCodeFence(text), Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("LLM 结构化输出不是合法 JSON：\n" + text, e);
        }
    }

    /**
     * 容忍模型把 JSON 包在 markdown 代码块里（```json ... ```）——真实链路常见行为，
     * 解析前剥掉最外层围栏。剥不掉原样返回（交给 readValue 报错，错误信息保留原始 text）。
     */
    private static String stripCodeFence(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return text;
    }
}
