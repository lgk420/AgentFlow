package com.agentflow.ability.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.agentflow.ability.llm.trace.LlmCallTrace;
import com.agentflow.ability.llm.trace.LlmTracer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SpringAiLlmClient#chatStructured 的行为测试（T4.2 / T4.4，QA 47）。
 *
 * <p>这三条以前在 {@code LlmNodeExecutorTest} 里**绕 Stub 客户端**验证——当时逻辑在共享的
 * {@code LlmStructuredChat}，桩只实现 {@code chat()}，所以真逻辑能被间接测到。
 * 逻辑并入客户端后（`chatStructured` 成了接口的抽象方法），再绕桩验证就变成"测桩自己"了，
 * 于是<b>改为直接测真实现</b>：mock 一个 {@link ChatModel} 喂回预置文本，
 * 断言拼进去的 prompt 与解析结果。
 *
 * <p>覆盖三件事：prompt 附 schema 指令；容忍 markdown 代码块围栏（docs/bugs/01）；非法 JSON 抛错。
 */
class SpringAiLlmClientChatStructuredTest {

    /**
     * 一个最小可用的 JSON Schema。
     */
    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"category\":{\"type\":\"string\"}},\"required\":[\"category\"]}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatModel chatModel = mock(ChatModel.class);
    private final RecordingLlmTracer tracer = new RecordingLlmTracer();
    private final SpringAiLlmClient client = new SpringAiLlmClient(chatModel, mapper, tracer);

    @Test
    void appendsSchemaInstruction_toPrompt() {
        stubModelReturns("{\"category\":\"ANALYSIS\"}");

        Map<String, Object> result = client.chatStructured("分类：练背", schema());

        assertThat(result).isEqualTo(Map.of("category", "ANALYSIS"));
        // 发给模型的是拼过 schema 指令的完整 prompt——tracer 记的 input 就是它
        assertThat(tracer.traces.get(0).input())
                .contains("分类：练背").contains("category").contains("必须只输出");
    }

    @Test
    void fencedJson_isTolerated() {
        // docs/bugs/01：真实链路模型会把 JSON 包进 markdown 代码块（```json ... ```），解析前剥围栏
        stubModelReturns("```json\n{\"category\":\"ANALYSIS\"}\n```");

        Map<String, Object> result = client.chatStructured("分类：练背", schema());

        assertThat(result).isEqualTo(Map.of("category", "ANALYSIS"));
        assertThat(tracer.traces.get(0).input()).contains("不要用 markdown 代码块");
    }

    @Test
    void invalidJson_throws() {
        stubModelReturns("这不是 JSON");

        assertThatThrownBy(() -> client.chatStructured("练背", schema()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是合法 JSON");
    }

    private JsonNode schema() {
        try {
            return mapper.readTree(SCHEMA);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("测试用 schema 本身不合法", e);
        }
    }

    private void stubModelReturns(String text) {
        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage message = mock(AssistantMessage.class);
        when(generation.getOutput()).thenReturn(message);
        when(message.getText()).thenReturn(text);
        when(response.getResult()).thenReturn(generation);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }

    /**
     * 记录型 Tracer（测试替身）：不打印，收进列表——顺带当"发给模型的 prompt 是什么"的抓手。
     */
    static class RecordingLlmTracer implements LlmTracer {

        final List<LlmCallTrace> traces = new ArrayList<>();

        @Override
        public void record(LlmCallTrace trace) {
            traces.add(trace);
        }
    }
}
