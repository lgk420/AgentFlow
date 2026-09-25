package com.agentflow.runtime.event;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7.6 验收：EventBus 接口纯语义，无 Redis Streams 类型泄漏——换 Kafka 只动实现类。
 *
 * <p>反射检查接口所有方法的参数/返回类型都不在 {@code org.springframework.data.redis} 包
 * （topic/group/consumer/messageId/EventMessage 都是通用语义概念，QA 71）。
 */
class EventBusPurityTest {

    @Test
    void interfaceHasNoRedisTypes() {
        for (Method m : EventBus.class.getMethods()) {
            assertThat(m.getReturnType().getName())
                    .as("方法 %s 返回类型不应泄漏 Redis", m.getName())
                    .doesNotStartWith("org.springframework.data.redis");
            for (Class<?> p : m.getParameterTypes()) {
                assertThat(p.getName())
                        .as("方法 %s 参数 %s 不应泄漏 Redis", m.getName(), p.getName())
                        .doesNotStartWith("org.springframework.data.redis");
            }
        }
    }
}
