package com.agentflow.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对话记忆配置（T10.1）——绑定 {@code agentflow.memory.*}。
 *
 * <p><b>默认开启</b>：传了 sessionId 才生效；不传 sessionId 的运行根本不碰记忆
 * （见 {@link MemoryStore}），所以开启不影响任何现有调用方。
 */
@Component
@ConfigurationProperties(prefix = "agentflow.memory")
public class MemoryProperties {

    /**
     * 是否启用记忆。关闭时 {@link MemoryStore} 的实现仍注册，但执行器不取不写。
     */
    private boolean enabled = true;

    /**
     * 每次取多少条历史喂给 prompt（读时截断，不删数据）。
     *
     * <p>取值权衡：太小则多轮上下文断裂；太大则 prompt 膨胀、成本上升。
     */
    private int historyLimit = 20;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getHistoryLimit() {
        return historyLimit;
    }

    public void setHistoryLimit(int historyLimit) {
        this.historyLimit = historyLimit;
    }
}
