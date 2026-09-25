package com.agentflow.ability.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

/**
 * Spring AI 实现的 LLM 网关（T4.1）。
 *
 * <p>框架调用全部封装在本类：两能力分别走 ChatModel 的低层 API；
 * 多 provider 由 Spring AI autoconfiguration 提供（application.yml 配 spring.ai.ollama / openai 等）。
 * 失败统一包装为 {@link LlmGatewayException}。
 *
 * <p><b>统一埋点（T4.5）</b>：每次调用计时 + 提取 model/token/输入输出 → 交 {@link Tracer}
 * （T4.5 用 {@link LoggingLlmTracer} 打印，P8 换 Redis/TraceSpan）。
 */
@Component
public class SpringAiLlmGateway implements LlmGateway {

    private final ChatModel chatModel;
    private final ObjectMapper mapper;
    private final Tracer tracer;

    public SpringAiLlmGateway(ChatModel chatModel, ObjectMapper mapper, Tracer tracer) {
        this.chatModel = chatModel;
        this.mapper = mapper;
        this.tracer = tracer;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        long start = System.nanoTime();
        try {
            ChatResponse response = chatModel.call(new Prompt(messages(systemPrompt, userPrompt)));
            String text = response.getResult().getOutput().getText();
            tracer.record(toTrace(start, response, userPrompt, text, 0));
            return text;
        } catch (Exception e) {
            tracer.record(toErrorTrace(start, e, userPrompt));
            throw new LlmGatewayException("LLM chat 调用失败", e);
        }
    }

    @Override
    public ChatResult chatWithTools(String systemPrompt, List<ChatMessage> history, List<ToolSpec> tools) {
        long start = System.nanoTime();
        try {
            List<Message> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(new SystemMessage(systemPrompt));
            }
            for (ChatMessage cm : history) {
                messages.add(toSpringMessage(cm));
            }
            List<ToolCallback> toolCallbacks = tools.stream()
                    .map(spec -> toToolCallback(spec))
                    .toList();
            ChatResponse response = chatModel.call(new Prompt(messages,
                    ToolCallingChatOptions.builder()
                            .toolCallbacks(toolCallbacks)
                            // Bug 03：关闭 Spring AI 内部工具执行——否则模型返回 tool call 时框架会自己调 FunctionToolCallback
                            // （其函数签名与 inputType 冲突 → ClassCastException），工具应由循环手动 ToolRegistry.invoke（QA 49 修订①）
                            .internalToolExecutionEnabled(false)
                            .build()));
            AssistantMessage output = response.getResult().getOutput();
            List<ToolCall> toolCalls = output.getToolCalls() == null ? List.of()
                    : output.getToolCalls().stream()
                            .map(tc -> new ToolCall(tc.id(), tc.name(), tc.arguments()))
                            .toList();
            tracer.record(toTrace(start, response, systemPrompt, output.getText(), toolCalls.size()));
            return new ChatResult(output.getText(), toolCalls);
        } catch (Exception e) {
            tracer.record(toErrorTrace(start, e, systemPrompt));
            throw new LlmGatewayException("LLM 工具对话失败", e);
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
    private Message toSpringMessage(ChatMessage cm) {
        return switch (cm.getRole()) {
            case USER -> new UserMessage(cm.getContent());
            case ASSISTANT -> {
                List<AssistantMessage.ToolCall> calls = cm.getToolCalls().isEmpty() ? List.of()
                        : cm.getToolCalls().stream()
                                .map(tc -> new AssistantMessage.ToolCall(tc.getId(), "function", tc.getName(), tc.getArguments()))
                                .toList();
                yield new AssistantMessage(cm.getContent() == null ? "" : cm.getContent(), Map.of(), calls);
            }
            case TOOL -> new ToolResponseMessage(List.of(
                    new ToolResponseMessage.ToolResponse(cm.getToolCallId(), cm.getToolName(), toResponseData(cm.getToolResult()))));
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
            throw new LlmGatewayException("工具结果序列化失败", e);
        }
    }

    /**
     * ToolSpec → Spring AI ToolCallback：FunctionToolCallback 只当 <b>schema 载体</b>
     * （function 抛错 = 框架不应执行工具，执行由循环手动 ToolRegistry.invoke，见 QA 49 修订①）。
     *
     * <p><b>必须同时设 {@code inputSchema} + {@code inputType}</b>（T4.5 踩坑：只设 inputSchema 时
     * Spring AI 1.0.0 的 {@code build()} 抛 "inputType cannot be null"——真实网关的 chatWithTools 一直潜伏此 bug，
     * T4.3 用 Stub 网关测没暴露）。inputType=Map.class 仅作占位。
     *
     * <p>函数类型用 {@code Function<Map,Object>} 对齐 inputType（Bug 03）：若内部工具执行被意外打开，
     * 框架按 inputType 反序列化参数成 Map 再调函数——用 Map 签名避免 "LinkedHashMap cannot be cast to String"
     * 的误导性错误，直接抛明确 IllegalStateException。
     */
    private static ToolCallback toToolCallback(ToolSpec spec) {
        return FunctionToolCallback.builder(spec.getName(), (Function<Map<String, Object>, Object>) input -> {
                    throw new IllegalStateException("工具应由 AgenticLoop 手动调用：" + spec.getName());
                })
                .description(spec.getDescription())
                .inputSchema(spec.getInputSchema())
                .inputType(Map.class)
                .build();
    }

    private static List<Message> messages(String systemPrompt, String userPrompt) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(userPrompt));
        return messages;
    }
}
