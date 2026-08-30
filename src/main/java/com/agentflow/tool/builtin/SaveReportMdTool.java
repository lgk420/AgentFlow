package com.agentflow.tool.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.agentflow.tool.annotation.AgentTool;
import org.springframework.stereotype.Component;

/**
 * 报告落盘工具（输出 markdown 化方案，见 QA 68）——把生成的报告保存为 md 文件。
 *
 * <p>由工作流的 TOOL 节点（如 fitness-coach 的 {@code save_md} 节点）确定性调用：取 report 节点输出、
 * 按 {@code reports/{场景}/report-{date}-{userId}.md} 落盘，返回路径。
 * 不用 agent 自主调用（QA 68 结论：存文件是确定性操作，走结构不走 LLM 决策）。
 *
 * <p>{@code scenario} 是工作流场景命名空间（不同工作流各占一个子目录），拒绝路径分隔符防逃逸。
 */
@Component
public class SaveReportMdTool {

    /**
     * 报告输出根目录（相对引擎工作目录）。
     */
    private static final String REPORTS_DIR = "reports";

    /**
     * scenario 缺省值（未指定时用 default 子目录）。
     */
    private static final String DEFAULT_SCENARIO = "default";

    /**
     * 保存报告为 markdown 文件。
     *
     * @param reportText 完整报告（markdown 文本），必填
     * @param date       训练日期（可缺省，用于文件名）
     * @param userId     用户 id（可缺省，用于文件名）
     * @param scenario   工作流场景名（决定 reports/ 下的子目录；可缺省）
     * @return 保存的相对路径
     */
    @AgentTool(name = "save_report_md",
            description = "把生成的教练报告保存为 markdown 文件（reports/{场景}/ 目录），返回保存路径")
    public String saveReportMd(String reportText, String date, String userId, String scenario) {
        if (reportText == null || reportText.isBlank()) {
            throw new IllegalArgumentException("reportText 不能为空");
        }
        String safeScenario = scenario == null || scenario.isBlank() ? DEFAULT_SCENARIO : scenario;
        if (safeScenario.contains("/") || safeScenario.contains("\\") || safeScenario.contains("..")) {
            throw new IllegalArgumentException("scenario 不能包含路径分隔符: " + scenario);
        }
        String safeDate = date == null || date.isBlank() ? "unknown" : date;
        String safeUser = userId == null || userId.isBlank() ? "default-user" : userId;
        String fileName = String.format("report-%s-%s.md", safeDate, safeUser);
        try {
            Files.createDirectories(Path.of(REPORTS_DIR, safeScenario));
            Path file = Path.of(REPORTS_DIR, safeScenario, fileName);
            Files.writeString(file, reportText, StandardCharsets.UTF_8);
            return file.toString();
        } catch (IOException e) {
            throw new IllegalStateException("保存报告失败: " + fileName, e);
        }
    }
}
