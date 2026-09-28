package com.agentflow.ability.llm;

import java.util.List;
import java.util.Map;

import com.agentflow.ability.llm.dto.LlmChatMessage;
import com.agentflow.ability.llm.dto.LlmChatResult;
import com.agentflow.ability.llm.dto.LlmToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * LLM 客户端抽象（P4，架构风险对策："LLM 相关全走 LlmGateway，框架调用集中在一个类"——
 * 引文是本接口的旧名，已改名 LlmClient：中文"网关"默认指 API 网关，会误导）。
 *
 * <p>执行器 / 路由只依赖本接口，不直接碰 Spring AI——换 provider、换实现、测试注入 mock 都只动这一个 seam。
 * 三能力对应后续子任务：{@link #chat}（T4.2 无 schema 的 LLM 节点）、{@link #chatStructured}
 * （T4.2 带 outputSchema / T4.4 路由返回 {nextNode}）、{@link #chatWithTools}（T4.3 AgenticLoop 手写循环）。
 *
 * <p>{@link #appendSchemaInstruction} / {@link #parseStructuredOutput} 是结构化输出的<b>两个半成品步骤</b>，
 * 供"单发一次"（{@link #chatStructured}）与"工具循环的出口"（AGENTIC_LOOP，QA 50）共用——
 * 循环那边调用方是执行器，它只认本接口，所以这两步必须摆在接口上。
 */
public interface LlmClient {

    /**
     * 纯文本对话。
     *
     * @param systemPrompt 系统提示词（可空）
     * @param userPrompt   用户提示词
     * @return 模型文本回复
     */
    String chat(String systemPrompt, String userPrompt);

    /**
     * 结构化输出。
     *
     * @param prompt 业务提示词（不含 schema 指令）
     * @param schema 输出结构（JSON Schema），拼进 prompt 约束模型
     */
    Map<String, Object> chatStructured(String prompt, JsonNode schema);

    /**
     * 把 schema 指令追加到 prompt 末尾（{@link #chatStructured} 内部第一步）。
     *
     * <p>单独暴露是给 AGENTIC_LOOP 用（QA 50）：循环要在一开始就把指令拼进 systemPrompt，
     * 这样每一轮都带着它，最后一轮模型才会按 JSON 作答。prompt 为空时只返回指令本身。
     */
    String appendSchemaInstruction(String prompt, JsonNode schema);

    /**
     * 模型输出 → Map（{@link #chatStructured} 内部第二步），容忍 markdown 代码块围栏。
     *
     * <p>单独暴露是给 AGENTIC_LOOP 的出口用（QA 50）：循环拿到最终文本后按同一套规则解析，
     * 不必为了转格式再调一次模型。
     */
    Map<String, Object> parseStructuredOutput(String text);

    /**
     * 带工具对话（T4.3 手写循环用）：返回文本或工具调用列表（模型可能要求调用工具，不直接回答）。
     *
     * @param systemPrompt 系统提示词（可空）
     * @param history      到目前为止的完整对话历史（含模型过往工具调用与工具返回结果，可空列表）
     * @param tools        本轮可用工具定义（name/description/schema，来自注册中心）
     */
    LlmChatResult chatWithTools(String systemPrompt, List<LlmChatMessage> history, List<LlmToolDefinition> tools);
}
