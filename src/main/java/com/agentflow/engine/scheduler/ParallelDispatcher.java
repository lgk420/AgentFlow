package com.agentflow.engine.scheduler;

import java.util.ArrayList;
import java.util.List;

import com.agentflow.engine.node.WorkflowExecutionException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 有界线程池 + 整批并发派发（QA 39 wave 模型）。
 *
 * <p>{@link #runAll} 提交全部任务并<b>等待全部完成</b>（含失败——先等完再统一处理），
 * 保证调度器能在批后顺序处理结果：节点执行只读 state、产出结果，写 state 收敛到调度线程，无并发写。
 *
 * <p>线程数由 {@code core.executor.parallelism} 配置（默认 4）。
 */
@Component
public class ParallelDispatcher implements DisposableBean {

    private final ExecutorService pool;

    public ParallelDispatcher(@Value("${core.executor.parallelism:4}") int parallelism) {
        this.pool = Executors.newFixedThreadPool(parallelism);
    }

    /**
     * 提交全部任务并行执行，等待全部完成后返回结果（按入参顺序）。
     *
     * <p>约定：任务自身不抛异常（逐节点异常在任务内捕获、放进结果）；
     * 若某个任务仍抛异常，仍等完全部，再把第一个异常包装为 {@link WorkflowExecutionException} 抛出。
     */
    public <T> List<T> runAll(List<Callable<T>> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(task));
        }
        List<T> results = new ArrayList<>(tasks.size());
        RuntimeException firstError = null;
        for (Future<T> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                firstError = new WorkflowExecutionException("并行执行被中断", e);
                results.add(null);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (firstError == null) {
                    firstError = cause instanceof RuntimeException r
                            ? r
                            : new WorkflowExecutionException("并行任务异常", cause);
                }
                results.add(null);
            }
        }
        if (firstError != null) {
            throw firstError;
        }
        return results;
    }

    @Override
    public void destroy() {
        pool.shutdown();
    }
}
