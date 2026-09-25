package com.agentflow.memory;

import com.agentflow.core.dsl.TemplateContextFactory;

/**
 * 测试便利（T10.2）——构造「不带记忆」的模板上下文工厂。
 *
 * <p>等价于不传 sessionId 的单次运行：memory 命名空间是空的，行为与加记忆之前一致。
 * 需要验证记忆的测试自己 new {@link TemplateContextFactory} 并注入带数据的
 * {@link StubMemoryStore}。
 */
public final class TestTemplateContext {

    private TestTemplateContext() {
    }

    public static TemplateContextFactory withoutMemory() {
        return new TemplateContextFactory(new StubMemoryStore(), new MemoryProperties());
    }
}
