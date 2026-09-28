package com.agentflow.ability.tool.scenarios.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.agentflow.ability.tool.annotation.ToolMethod;
import org.springframework.stereotype.Component;

/**
 * 报告落盘工具（输出 markdown 化方案，见 QA 68）——把生成的报告保存为 md 文件。
 *
 * <p>由工作流的 TOOL 节点（如 fitness-coach 的 {@code tool_report_markdown_node}）确定性调用：
 * 取报告节点输出、按 {@code reports/{workflowId}/report-{date}-{userId}.md} 落盘，返回路径。
 * 不用 agent 自主调用（QA 68 结论：存文件是确定性操作，走结构不走 LLM 决策）。
 *
 * <p>{@code workflowId} 决定 reports/ 下的子目录——**不同工作流各占一个子目录**，报告不会混在一起。
 * 值由 {@code start} 节点的输出给（{@code {{nodes.start.output.workflowId}}}），不在 DSL 里硬编码：
 * 硬编码一个跟工作流 {@code id} 重复的常量，改了名字就会忘着同步。拒绝路径分隔符防逃逸。
 */
@Component
public class FileTools {

    /**
     * 报告输出根目录（相对引擎工作目录）。
     */
    private static final String REPORTS_DIR = "reports";

    /**
     * workflowId 缺省值——正常不该走到（DSL 总从 start 节点取），兜个底而已。
     */
    private static final String DEFAULT_WORKFLOW_ID = "default";

    /**
     * 保存报告为 markdown 文件。
     *
     * @param reportText 完整报告（markdown 文本）
     * @param date       训练日期（可缺省，用于文件名）
     * @param userId     用户 id（可缺省，用于文件名）
     * @param workflowId 工作流 id，决定 reports/ 下的子目录（可缺省）
     * @return 保存的相对路径
     */
    @ToolMethod(name = "report_markdown",
            description = "把生成的教练报告保存为 markdown 文件（reports/{workflowId}/ 目录），返回保存路径")
    public String reportMarkdown(String reportText, String date, String userId, String workflowId) {
        if (reportText == null || reportText.isBlank()) {
            throw new IllegalArgumentException("reportText 不能为空");
        }
        String safeWorkflowId = workflowId == null || workflowId.isBlank() ? DEFAULT_WORKFLOW_ID : workflowId;
        if (safeWorkflowId.contains("/") || safeWorkflowId.contains("\\") || safeWorkflowId.contains("..")) {
            throw new IllegalArgumentException("workflowId 不能包含路径分隔符: " + workflowId);
        }
        String safeDate = date == null || date.isBlank() ? "unknown" : date;
        String safeUser = userId == null || userId.isBlank() ? "default-user" : userId;
        String fileName = String.format("report-%s-%s.md", safeDate, safeUser);
        try {
            Files.createDirectories(Path.of(REPORTS_DIR, safeWorkflowId));
            Path file = Path.of(REPORTS_DIR, safeWorkflowId, fileName);
            Files.writeString(file, reportText, StandardCharsets.UTF_8);
            return file.toString();
        } catch (IOException e) {
            throw new IllegalStateException("保存报告失败: " + fileName, e);
        }
    }
}
