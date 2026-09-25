package com.agentflow.ability.llm;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4.1 LLM 网关接口验收测试（走 StubLlmGateway 测试替身，QA 36）。
 *
 * <p>真实 Ollama 对话需本机装 Ollama + 拉模型后另跑（验收记录于任务拆解 T4.1）。
 */
class LlmGatewayTest {

    private final StubLlmGateway gateway = new StubLlmGateway();

    @Test
    void chat_returnsConfiguredText() {
        gateway.setTextOutput("你好，我是教练");
        assertThat(gateway.chat("system", "hi")).isEqualTo("你好，我是教练");
    }

    @Test
    void chatWithTools_noToolCallsByDefault() {
        ChatResult result = gateway.chatWithTools("system", List.of(), List.of(new ToolSpec("tool_a", "desc", null)));
        assertThat(result.getText()).isEqualTo("stub 回复");
        assertThat(result.wantsTools()).isFalse();
    }

    @Test
    void chatWithTools_scriptedDialogues_returnInOrder() {
        gateway.setToolDialogues(
                new ChatResult(null, List.of(new ToolCall("call_1", "tool_a", "{}"))),
                new ChatResult("最终答案", List.of()));

        ChatResult first = gateway.chatWithTools("system", List.of(), List.of(new ToolSpec("tool_a", "desc", null)));
        assertThat(first.wantsTools()).isTrue();
        assertThat(first.getToolCalls()).hasSize(1);
        assertThat(first.getToolCalls().get(0).getId()).isEqualTo("call_1");
        assertThat(first.getToolCalls().get(0).getName()).isEqualTo("tool_a");

        ChatResult second = gateway.chatWithTools("system", List.of(), List.of(new ToolSpec("tool_a", "desc", null)));
        assertThat(second.getText()).isEqualTo("最终答案");
        assertThat(second.wantsTools()).isFalse();

        // 队列耗尽 → 回落默认文本（无工具调用）
        ChatResult third = gateway.chatWithTools("system", List.of(), List.of(new ToolSpec("tool_a", "desc", null)));
        assertThat(third.getText()).isEqualTo("stub 回复");
        assertThat(third.wantsTools()).isFalse();
    }
}
