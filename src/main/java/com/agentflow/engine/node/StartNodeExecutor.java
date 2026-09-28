package com.agentflow.engine.node;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import com.agentflow.engine.model.definition.NodeDefinition;
import com.agentflow.engine.model.definition.NodeType;
import com.agentflow.engine.model.state.WorkflowState;
import org.springframework.stereotype.Component;

/**
 * START 执行器：运行入口，产出**本次运行的环境信息**。
 *
 * <p>放这里的东西有一条界线：<b>引擎知道、而调用方不会也不必告诉你的</b>。
 * 调用方给的那些（{@code userId} / {@code userMessage} / {@code sessionId}）留在 {@code state.inputs}，
 * 不在这里转发一遍——否则同一样东西会有两种取法，早晚混用。
 *
 * <p>由此 START 不再是"无输出的放行门槛"，它的输出是：
 * <ul>
 *   <li>{@code today} —— 本次运行的日期，{@code YYYYMMDD}（与 DSL 里 {@code date} 的格式一致）。
 *       日志文本里没写日期时，解析节点拿它兜底——**LLM 自己不知道"今天"是几号，只能编**，
 *       而日期是训练日志的存储主键。</li>
 *   <li>{@code workflowId} —— 本次运行的工作流 id。落盘这类"按工作流分目录"的场景用它，
 *       省得在 DSL 里硬编码一个跟 {@code id} 重复的常量（改了工作流名就会忘）。</li>
 * </ul>
 *
 * <p>模板里取 {@code {{nodes.start.output.today}}} / {@code {{nodes.start.output.workflowId}}}。
 * START 是第一个执行的节点，**它的输出对图里任何节点永远就绪——引用它不需要拉边**。
 */
@Component
public class StartNodeExecutor implements NodeExecutor {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    @Override
    public NodeType type() {
        return NodeType.START;
    }

    @Override
    public Object execute(NodeDefinition node, WorkflowState state) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("workflowId", state.getWorkflowId());
        env.put("today", LocalDate.now().format(DATE_FMT));
        return env;
    }
}
