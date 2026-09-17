package com.agentflow.runtime.checkpoint;

import java.util.function.Function;

import com.agentflow.core.state.WorkflowState;

/**
 * Checkpoint 存储：一次运行的快照的存取。
 *
 * <p><b>并发语义（Bug 08）</b>：事件驱动下多个 node-worker 并发写同一 run。早期实现只有整份覆盖的
 * {@code save}，而 {@code NodeWorker} 是「读-改-写」——两个 worker 各自 load 到同一份快照、各自整份写回，
 * 后写者覆盖先写者已提交的节点输出（丢失更新）。注意幂等键防的是「重复」，防不住「丢失」，两者是不同问题。
 *
 * <p>因此本接口<b>不提供整份覆盖写</b>，只提供两个写入口：
 * <ul>
 *   <li>{@link #create}：run 初始化（仅新建，已存在则失败）；</li>
 *   <li>{@link #update}：并发路径的唯一写入口，内部以 version 做 CAS，冲突则重试。</li>
 * </ul>
 *
 * <p>{@code version} 是乐观并发控制版本号，由实现维护，调用方不要自行设置。
 */
public interface CheckpointStore {

    /**
     * 新建一次运行的初始快照。仅用于 run 初始化；同 runId 已存在时抛异常（防止误覆盖在跑的状态）。
     */
    void create(WorkflowState state);

    /**
     * 对指定 run 做一次原子更新：load → 应用 mutator → CAS 提交，冲突则整个重来（<b>mutator 会被再次调用</b>）。
     *
     * <p>调用方只需表达「我产生了这些事实」，并发控制、重试、版本推进都由实现负责。
     *
     * <p><b>两条契约（违反会重新引入 Bug 08）</b>：
     * <ol>
     *   <li><b>mutator 必须幂等</b>——它可能因 CAS 冲突被执行多次。把「自己产生的事实」写进 state
     *       （如 {@code nodeOutputs.put(nodeId, out)}）天然幂等；但<b>不要在 mutator 里调 LLM</b>，
     *       昂贵且非幂等的计算应在调用 {@code update} 之前算好、作为常量捕获进来。</li>
     *   <li><b>派生结果必须在 mutator 内、基于传入的这份最新快照计算</b>。若在 {@code update} 之前用旧
     *       快照算好结果、只把结果搬进来，会漏掉「别人刚提交的事实」而产生<b>漏发</b>（lost wakeup）：
     *       fan-in 节点 Z 的两条入边分别由两个 worker 解析，各自在旧快照上都看到「对边尚未解析」
     *       而判定 Z 不就绪，于是谁都不发 NodeReady(Z)，run 卡死在 Z。</li>
     * </ol>
     *
     * @param mutator 在最新快照上应用改动，返回需要透出的结果（例如「是否真的发生了终态跃迁」）
     * @return mutator 的返回值
     * @throws IllegalStateException run 不存在或 CAS 重试超限
     */
    <T> T update(String runId, Function<WorkflowState, T> mutator);

    /**
     * 按 runId 读状态快照；不存在返回 null。返回副本，调用方改动不影响存储。
     */
    WorkflowState load(String runId);
}
