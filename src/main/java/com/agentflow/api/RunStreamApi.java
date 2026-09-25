package com.agentflow.api;

import java.io.IOException;
import java.util.function.Consumer;

import com.agentflow.engine.model.state.RunStatus;
import com.agentflow.engine.model.state.WorkflowState;
import com.agentflow.runtime.checkpoint.CheckpointStore;
import com.agentflow.runtime.progress.RunProgress;
import com.agentflow.runtime.progress.RunProgressBus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 运行进度流（T10.4）——SSE 推送「哪个节点跑完了」「运行什么时候结束」。
 *
 * <p><b>推的是进度，不是 token</b>：工作流在 worker 线程异步执行，而 SSE 要在请求线程持续写响应，
 * 两者天然错位。所以这里走<b>节点粒度</b>的进度推送（模型正在决定走哪条边、哪个节点刚跑完），
 * 而不是 token 级打字机。这与引擎「节点是执行单元」的粒度是一致的。
 *
 * <p><b>语义边界</b>：推的是<b>订阅之后</b>发生的进度；订阅前已完成的节点不会补发
 * （要完整状态请用 {@code GET /runs/{runId}}）。连接时若运行已到终态，会立刻推一条终态并关闭。
 */
@RestController
@RequestMapping("/api/v1/runs")
public class RunStreamApi {

    private final RunProgressBus progressBus;
    private final CheckpointStore checkpointStore;

    public RunStreamApi(RunProgressBus progressBus, CheckpointStore checkpointStore) {
        this.progressBus = progressBus;
        this.checkpointStore = checkpointStore;
    }

    /**
     * 订阅某个运行的进度（SSE）。事件名 {@code progress}，data 是 {@link RunProgress} 的 JSON。
     *
     * <p>运行到达终态时服务端主动关闭连接——客户端不必自己判断何时断开。
     */
    @GetMapping(path = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String runId) {
        WorkflowState current = checkpointStore.load(runId);
        if (current == null) {
            throw new IllegalArgumentException("运行不存在：" + runId);
        }

        // 0 = 不设超时：连接该活多久由「运行到终态」决定，而不是墙钟时间
        SseEmitter emitter = new SseEmitter(0L);

        // 已经跑完了：推一条终态就关，别让客户端干等一个不会再来的事件
        if (current.getStatus() != RunStatus.RUNNING) {
            send(emitter, RunProgress.runCompleted(runId, current.getStatus().name(), current.getError()));
            emitter.complete();
            return emitter;
        }

        Consumer<RunProgress> listener = progress -> {
            send(emitter, progress);
            if (progress.type() == RunProgress.Type.RUN_COMPLETED) {
                emitter.complete();
            }
        };
        progressBus.subscribe(runId, listener);

        // 三种断开方式都要退订，否则订阅者列表会随连接泄漏
        emitter.onCompletion(() -> progressBus.unsubscribe(runId, listener));
        emitter.onTimeout(() -> progressBus.unsubscribe(runId, listener));
        emitter.onError(e -> progressBus.unsubscribe(runId, listener));
        return emitter;
    }

    /**
     * 写一条事件。客户端已断开时 send 抛 IOException——那是常态（用户关页面），
     * 关掉这个 emitter 即可，不该往上冒泡。
     */
    private static void send(SseEmitter emitter, RunProgress progress) {
        try {
            emitter.send(SseEmitter.event().name("progress").data(progress));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }
}
