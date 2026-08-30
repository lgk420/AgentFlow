package com.agentflow.core.dsl;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.agentflow.core.model.WorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 健身教练助手工作流 fixture（testdata/workflows/legacy-fitness-coach/fitness-coach.json）DSL 合法性守护测试。
 *
 * <p>用真实的 {@link GraphParser} + {@link GraphValidator} 校验：节点引用存在、恰好一个 START/END、
 * 无环、边类型白名单等——确保目标 DSL 随时是合法可运行的（工具注册属运行期能力，GraphValidator 不校验）。
 */
class DemoWorkflowValidationTest {

    @Test
    void fitnessCoachWorkflow_isDslValid() throws Exception {
        String json = new String(
                getClass().getResourceAsStream("/testdata/workflows/legacy-fitness-coach/fitness-coach.json").readAllBytes(),
                StandardCharsets.UTF_8);

        WorkflowDefinition wf = new GraphParser(new ObjectMapper()).parse(json);
        List<String> errors = new GraphValidator().validate(wf);

        assertThat(errors).as("DSL 校验错误: %s", errors).isEmpty();
    }
}
