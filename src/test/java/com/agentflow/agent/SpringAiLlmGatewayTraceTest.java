package com.agentflow.agent;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.5 网关埋点测试——{@link SpringAiLlmGateway} 每次调用都记一条 {@link LlmCallTrace}。
 *
 * <p>用 Mockito 全 mock（不构造真实 Spring AI 响应对象），断言 trace 含 model / token / 输入输出 / 工具调用数；
 * 调用失败也记一条 ERROR trace。
 */
class SpringAiLlmGatewayTraceTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final RecordingTracer tracer = new RecordingTracer();
    private final SpringAiLlmGateway gateway = new SpringAiLlmGateway(chatModel, new ObjectMapper(), tracer);

    @Test
    void chat_recordsTrace_withModelTokensInputOutput() {
        // 先构造响应再 stub（避免 when 参数里嵌套 stub 触发 UnfinishedStubbing）
        ChatResponse response = chatResponse("你好", "test-model", 5, 3, List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        String result = gateway.chat("sys", "用户输入");

        assertThat(result).isEqualTo("你好");
        assertThat(tracer.traces).hasSize(1);
        LlmCallTrace t = tracer.traces.get(0);
        assertThat(t.model()).isEqualTo("test-model");
        assertThat(t.tokensIn()).isEqualTo(5);
        assertThat(t.tokensOut()).isEqualTo(3);
        assertThat(t.input()).isEqualTo("用户输入");
        assertThat(t.output()).isEqualTo("你好");
        assertThat(t.toolCallCount()).isZero();
        assertThat(t.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void chatWithoutUsage_keepsTokensNull() {
        ChatResponse response = chatResponse("ok", "m", null, null, List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        gateway.chat("sys", "x");

        assertThat(tracer.traces.get(0).tokensIn()).isNull();
        assertThat(tracer.traces.get(0).tokensOut()).isNull();
    }

    @Test
    void chatWithTools_recordsToolCallCount_andSystemPromptAsInput() {
        ChatResponse response = chatResponse("我要调用工具", "m", null, null,
                List.of(new AssistantMessage.ToolCall("call_1", "function", "calc", "{}")));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        // schema 需带 properties（Spring AI FunctionToolCallback 要求能推断 inputType）
        gateway.chatWithTools("系统提示", List.of(),
                List.of(new ToolSpec("calc", "两数运算", "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"number\"}}}")));

        assertThat(tracer.traces).hasSize(1);
        assertThat(tracer.traces.get(0).toolCallCount()).isEqualTo(1);
        assertThat(tracer.traces.get(0).input()).isEqualTo("系统提示");
    }

    @Test
    void chatWithTools_disablesInternalToolExecution() {
        // Bug 03：必须关闭 Spring AI 内部工具执行——否则模型返回 tool call 时框架会自己调 FunctionToolCallback
        // （函数签名与 inputType 冲突 → ClassCastException），工具应由循环手动调
        ChatResponse response = chatResponse("我要调用工具", "m", null, null,
                List.of(new AssistantMessage.ToolCall("call_1", "function", "calc", "{}")));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        gateway.chatWithTools("系统提示", List.of(),
                List.of(new ToolSpec("calc", "两数运算", "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"number\"}}}")));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        ToolCallingChatOptions options = (ToolCallingChatOptions) captor.getValue().getOptions();
        assertThat(options.getInternalToolExecutionEnabled()).isFalse();
    }

    @Test
    void chat_error_recordsErrorTrace() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("网络挂了"));

        assertThatThrownBy(() -> gateway.chat("sys", "输入")).isInstanceOf(LlmGatewayException.class);

        assertThat(tracer.traces).hasSize(1);
        assertThat(tracer.traces.get(0).output()).contains("ERROR").contains("网络挂了");
        assertThat(tracer.traces.get(0).input()).isEqualTo("输入");
    }

    /**
     * 记录型 Tracer（测试替身）：不打印，收进列表供断言。
     */
    static class RecordingTracer implements Tracer {

        final List<LlmCallTrace> traces = new ArrayList<>();

        @Override
        public void record(LlmCallTrace trace) {
            traces.add(trace);
        }
    }

    private static ChatResponse chatResponse(String text, String model, Integer tokensIn, Integer tokensOut,
                                             List<AssistantMessage.ToolCall> toolCalls) {
        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage assistantMessage = mock(AssistantMessage.class);
        when(generation.getOutput()).thenReturn(assistantMessage);
        when(assistantMessage.getText()).thenReturn(text);
        when(assistantMessage.getToolCalls()).thenReturn(toolCalls);
        when(response.getResult()).thenReturn(generation);

        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getModel()).thenReturn(model);
        if (tokensIn != null || tokensOut != null) {
            Usage usage = mock(Usage.class);
            when(usage.getPromptTokens()).thenReturn(tokensIn);
            when(usage.getCompletionTokens()).thenReturn(tokensOut);
            when(metadata.getUsage()).thenReturn(usage);
        }
        when(response.getMetadata()).thenReturn(metadata);
        return response;
    }
}
