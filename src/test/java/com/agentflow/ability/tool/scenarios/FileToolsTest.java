package com.agentflow.ability.tool.scenarios;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.agentflow.ability.tool.scenarios.common.FileTools;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T5.4 系列 · ReportMarkdownTool 单测（QA 68 输出 markdown 化方案，TOOL 节点确定性落盘）。
 *
 * <p>验证：按工作流子目录落盘（{@code reports/{workflowId}/}）、内容正确、返回路径；空 reportText 报错；
 * workflowId / date / userId 缺省回落；workflowId 含路径分隔符拒绝。
 * 测试会真实写 reports/ 目录，用后删除自己的文件。
 */
class FileToolsTest {

    private final FileTools tool = new FileTools();

    @Test
    void savesMarkdown_underWorkflowDir_andReturnsPath() throws IOException {
        String path = tool.reportMarkdown("# 训练报告\n\n测试内容", "20260829", "test-user", "fitness-coach");

        assertThat(path).contains("fitness-coach").contains("report-20260829-test-user.md"); // 路径含分隔符，只断言工作流+文件名
        Path file = Path.of(path);
        assertThat(Files.readString(file)).isEqualTo("# 训练报告\n\n测试内容");
        Files.deleteIfExists(file);
    }

    @Test
    void blankReportText_throws() {
        assertThatThrownBy(() -> tool.reportMarkdown("  ", "20260829", "u", "w"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reportText");
    }

    @Test
    void missingWorkflowIdDateUser_useDefaults() throws IOException {
        String path = tool.reportMarkdown("报告", null, null, null);

        assertThat(path).contains("default").contains("report-unknown-default-user.md");
        assertThat(Files.exists(Path.of(path))).isTrue();
        Files.deleteIfExists(Path.of(path));
    }

    @Test
    void workflowIdWithPathSeparator_throws() {
        assertThatThrownBy(() -> tool.reportMarkdown("报告", "d", "u", "a/b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workflowId");
    }
}
