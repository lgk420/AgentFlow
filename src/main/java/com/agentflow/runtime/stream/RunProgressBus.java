package com.agentflow.runtime.stream;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 运行进度的进程内广播（T10.4）——SSE 端点订阅、worker 发布。
 *
 * <p><b>为什么不复用 Redis 的 {@code EventBus}</b>：那是驱动执行的内部消息，走<b>消费组</b>——
 * 组内是<b>竞争消费</b>，SSE 端点若加入同组会抢走 worker 的消息、把执行搞乱。
 * 要广播得绕开消费组用 {@code XREAD}，对本项目（单实例演示）是没必要的复杂度。
 *
 * <p><b>局限（要说清楚）</b>：进程内意味着<b>多实例部署时，SSE 连接与 worker 可能不在同一进程</b>，
 * 那时收不到进度。届时应改为订阅 Redis 上的广播流（{@code EventBus} 已经在了，加一个只读通道即可）。
 */
@Component
public class RunProgressBus {

    private static final Logger log = LoggerFactory.getLogger(RunProgressBus.class);

    /**
     * runId → 订阅者列表。用 {@link CopyOnWriteArrayList}：订阅/退订远少于发布，
     * 且发布时要遍历——读多写少的典型场景。
     */
    private final Map<String, List<Consumer<RunProgress>>> subscribers = new ConcurrentHashMap<>();

    /** 订阅某个运行的进度。调用方负责在连接结束时退订（见 RunStreamApi 的 onCompletion/onTimeout）。 */
    public void subscribe(String runId, Consumer<RunProgress> listener) {
        subscribers.computeIfAbsent(runId, key -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void unsubscribe(String runId, Consumer<RunProgress> listener) {
        List<Consumer<RunProgress>> listeners = subscribers.get(runId);
        if (listeners == null) {
            return;
        }
        listeners.remove(listener);
        if (listeners.isEmpty()) {
            subscribers.remove(runId);
        }
    }

    /**
     * 广播进度。
     *
     * <p><b>单个订阅者异常不影响其他订阅者，更不影响 worker</b>——进度推送是旁路，
     * 它出错绝不能让节点执行失败（客户端断开是常态，不该因此中断运行）。
     */
    public void publish(RunProgress progress) {
        List<Consumer<RunProgress>> listeners = subscribers.get(progress.runId());
        if (listeners == null) {
            return;
        }
        for (Consumer<RunProgress> listener : listeners) {
            try {
                listener.accept(progress);
            } catch (RuntimeException e) {
                log.debug("推送运行进度失败（runId={}, type={}）：{}", progress.runId(), progress.type(), e.getMessage());
            }
        }
    }
}
