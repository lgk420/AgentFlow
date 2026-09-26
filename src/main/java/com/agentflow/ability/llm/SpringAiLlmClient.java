package com.agentflow.ability.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.agentflow.ability.llm.dto.LlmChatMessage;
import com.agentflow.ability.llm.dto.LlmChatResult;
import com.agentflow.ability.llm.dto.LlmToolCall;
import com.agentflow.ability.llm.dto.LlmToolDefinition;
import com.agentflow.ability.llm.trace.LlmCallTrace;
import com.agentflow.ability.llm.trace.LogLlmTracer;
import com.agentflow.ability.llm.trace.LlmTracer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * Spring AI 实现的 LLM 客户端（T4.1）。
 *
 * <p>框架调用全部封装在本类：两能力分别走 ChatModel 的低层 API；
 * 多 provider 由 Spring AI autoconfiguration 提供（application.yml 配 spring.ai.ollama / openai 等）。
 * 失败统一包装为 {@link LlmClientException}。
 *
 * <p><b>统一埋点（T4.5）</b>：每次调用计时 + 提取 model/token/输入输出 → 交 {@link LlmTracer}
 * （T4.5 用 {@link LogLlmTracer} 打印，P8 换 Redis/TraceSpan）。
 */
@Component
public class SpringAiLlmClient implements LlmClient {

    private final ChatModel chatModel;
    private final ObjectMapper mapper;
    private final LlmTracer llmTracer;

    public SpringAiLlmClient(ChatModel chatModel, ObjectMapper mapper, LlmTracer llmTracer) {
        this.chatModel = chatModel;
        this.mapper = mapper;
        this.llmTracer = llmTracer;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        long start = System.nanoTime();
        try {
            ChatResponse response = chatModel.call(new Prompt(toSpringAiMessages(systemPrompt, userPrompt)));
            String text = response.getResult().getOutput().getText();
            llmTracer.record(toTrace(start, response, userPrompt, text, 0));
            return text;
        } catch (Exception e) {
            llmTracer.record(toErrorTrace(start, e, userPrompt));
            throw new LlmClientException("LLM chat 调用失败", e);
        }
    }

    private List<Message> toSpringAiMessages(String systemPrompt, String userPrompt) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(userPrompt));
        return messages;
    }

    @Override
    public Map<String, Object> chatStructured(String prompt, JsonNode schema) {
        String fullPrompt = prompt
                + "\n\n你必须只输出一个 JSON 对象，符合以下 schema。直接输出 JSON，"
                + "不要用 markdown 代码块（不要 ```json 围栏）：\n" + schema;
        String text = this.chat(null, fullPrompt);
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
    private String stripCodeFence(String text) {
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

    @Override
    public LlmChatResult chatWithTools(String systemPrompt, List<LlmChatMessage> history, List<LlmToolDefinition> tools) {
        long start = System.nanoTime();
        try {
            List<Message> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(new SystemMessage(systemPrompt));
            }
            for (LlmChatMessage cm : history) {
                messages.add(toSpringAiMessage(cm));
            }
            List<ToolCallback> toolCallbacks = tools.stream()
                    .map(LlmToolDefinition::toToolCallback)
                    .toList();
            ChatResponse response = chatModel.call(new Prompt(messages,
                    ToolCallingChatOptions.builder()
                            .toolCallbacks(toolCallbacks)
                            // Bug 03：关闭 Spring AI 内部工具执行——否则模型返回 tool call 时框架会自己调 FunctionToolCallback
                            // （其函数签名与 inputType 冲突 → ClassCastException），工具应由循环手动 ToolRegistry.invoke（QA 49 修订①）
                            .internalToolExecutionEnabled(false)
                            .build()));
            AssistantMessage output = response.getResult().getOutput();
            List<LlmToolCall> llmToolCalls = output.getToolCalls().stream()
                    .map(toolCall -> new LlmToolCall(toolCall.id(), toolCall.name(), toolCall.arguments()))
                    .toList();
            llmTracer.record(toTrace(start, response, systemPrompt, output.getText(), llmToolCalls.size()));
            return new LlmChatResult(output.getText(), llmToolCalls);
        } catch (Exception e) {
            llmTracer.record(toErrorTrace(start, e, systemPrompt));
            throw new LlmClientException("LLM 工具对话失败", e);
        }
    }

    /**
     * 成功调用 → trace：model/耗时/token（provider 返回才有，否则 null）/输入输出/工具调用数。
     */
    private LlmCallTrace toTrace(long start, ChatResponse response, String input, String output, int toolCallCount) {
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        Integer tokensIn = usage == null ? null : usage.getPromptTokens();
        Integer tokensOut = usage == null ? null : usage.getCompletionTokens();
        String model = metadata == null ? null : metadata.getModel();
        return new LlmCallTrace(model, latencyMs, tokensIn, tokensOut, input, output, toolCallCount);
    }

    /**
     * 失败调用 → trace：输出为错误信息（tokens 无，模型名未知）。
     */
    private LlmCallTrace toErrorTrace(long start, Exception e, String input) {
        return new LlmCallTrace(null, (System.nanoTime() - start) / 1_000_000,
                null, null, input, "ERROR: " + e.getMessage(), 0);
    }

    /**
     * 轻量 ChatMessage → Spring AI Message（QA 49）。ASSISTANT 携带的工具调用原样回放（含 id），
     * TOOL 结果序列化成 JSON 字符串后按 id 配对成 {@link ToolResponseMessage.ToolResponse}。
     */
    private Message toSpringAiMessage(LlmChatMessage llmChatMessage) {
        return switch (llmChatMessage.getRole()) {
            case USER -> new UserMessage(llmChatMessage.getContent());
            case ASSISTANT -> {
                List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
                for (LlmToolCall llmToolCall : llmChatMessage.getLlmToolCalls()) {
                    toolCalls.add(new AssistantMessage.ToolCall(llmToolCall.getId(), "function", llmToolCall.getName(), llmToolCall.getArguments()));
                }
                yield new AssistantMessage(llmChatMessage.getContent() == null
                        ? "" : llmChatMessage.getContent(), Map.of(), toolCalls);
            }
            case TOOL -> new ToolResponseMessage(List.of(
                    new ToolResponseMessage.ToolResponse(llmChatMessage.getToolCallId(), llmChatMessage.getToolName(), toResponseData(llmChatMessage.getToolResult()))));
        };
    }

    /**
     * 工具结果 → tool 消息内容字符串：String 原样，其余 JSON 序列化（ToolResponse.responseData 是 String）。
     */
    private String toResponseData(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof String s) {
            return s;
        }
        try {
            return mapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new LlmClientException("工具结果序列化失败", e);
        }
    }
}
