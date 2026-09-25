package com.agentflow.runtime.stream;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * T10.4 运行进度总线单测——订阅/发布/隔离/容错。
 *
 * <p>用单测覆盖是刻意的：E2E 里「订阅后运行才推进」有竞态（运行可能已经跑完），
 * 单测能确定性地验证增量推送本身。
 */
class RunProgressBusTest {

    private final RunProgressBus bus = new RunProgressBus();

    @Test
    void subscriber_receivesProgressOfItsRun() {
        List<RunProgress> received = new ArrayList<>();
        bus.subscribe("run-1", received::add);

        bus.publish(RunProgress.nodeCompleted("run-1", "parse", "{\"muscleGroup\":\"腿\"}"));
        bus.publish(RunProgress.runCompleted("run-1", "SUCCEEDED", null));

        assertThat(received).hasSize(2);
        assertThat(received.get(0).type()).isEqualTo(RunProgress.Type.NODE_COMPLETED);
        assertThat(received.get(0).nodeId()).isEqualTo("parse");
        assertThat(received.get(1).type()).isEqualTo(RunProgress.Type.RUN_COMPLETED);
        assertThat(received.get(1).status()).isEqualTo("SUCCEEDED");
    }

    /** 只收到自己那个 run 的进度——否则多个客户端的进度会互相串。 */
    @Test
    void subscribers_areIsolatedByRunId() {
        List<RunProgress> run1 = new ArrayList<>();
        List<RunProgress> run2 = new ArrayList<>();
        bus.subscribe("run-1", run1::add);
        bus.subscribe("run-2", run2::add);

        bus.publish(RunProgress.nodeCompleted("run-1", "a", null));

        assertThat(run1).hasSize(1);
        assertThat(run2).isEmpty();
    }

    @Test
    void unsubscribe_stopsDelivery() {
        List<RunProgress> received = new ArrayList<>();
        java.util.function.Consumer<RunProgress> listener = received::add;
        bus.subscribe("run-1", listener);
        bus.publish(RunProgress.nodeCompleted("run-1", "a", null));

        bus.unsubscribe("run-1", listener);
        bus.publish(RunProgress.nodeCompleted("run-1", "b", null));

        assertThat(received).hasSize(1);
    }

    /**
     * 单个订阅者抛异常不能影响其他订阅者，更不能让发布方（worker）失败——
     * 客户端断开是常态，不该因此中断运行。
     */
    @Test
    void failingSubscriber_doesNotBreakOthersNorPublisher() {
        List<RunProgress> healthy = new ArrayList<>();
        bus.subscribe("run-1", progress -> {
            throw new IllegalStateException("模拟客户端已断开");
        });
        bus.subscribe("run-1", healthy::add);

        assertThatCode(() -> bus.publish(RunProgress.nodeCompleted("run-1", "a", null)))
                .doesNotThrowAnyException();
        assertThat(healthy).hasSize(1);
    }

    @Test
    void publish_withoutSubscribers_isNoOp() {
        assertThatCode(() -> bus.publish(RunProgress.nodeCompleted("nobody-listening", "a", null)))
                .doesNotThrowAnyException();
    }
}
