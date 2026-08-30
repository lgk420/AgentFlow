package com.agentflow.core.routing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.agentflow.core.state.WorkflowState;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

/**
 * CONDITIONAL 边条件求值器（T2.4）。
 *
 * <p>DSL 条件形如 {@code {{nodes.analysis_llm.output.trend}} == 'DOWN'}——把 {@code {{path}}} 占位符
 * 剥成内部路径，其余按 SpEL 语法原样求值：
 * <pre>
 *   {{nodes.analysis_llm.output.trend}} == 'DOWN'
 * →  nodes.analysis_llm.output.trend == 'DOWN'
 * </pre>
 * root 绑定 {@code {inputs: state.inputs, nodes: state.nodeOutputs}}，SpEL 对 Map / bean 属性链式访问天然支持：
 * {@code nodes.analysis_llm} → {@code nodeOutputs.get("analysis_llm")}（Map）、
 * {@code .output} → {@code getOutput()}（bean）、{@code .trend} → {@code get("trend")}（Map）。
 *
 * <p>SpEL 解析/求值失败抛 RuntimeException（含原始条件），由调度器捕获并使 run FAILED（坏配置尽早暴露）。
 */
@Component
public class ConditionEvaluator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]*)\\}\\}");

    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * 对 state 求值条件，返回是否满足（true 才走该 CONDITIONAL 边）。
     */
    public boolean evaluate(String condition, WorkflowState state) {
        try {
            Expression expression = parser.parseExpression(translate(condition));
            StandardEvaluationContext context = new StandardEvaluationContext();
            // SpEL 默认不把 Map 的 .key 当 get("key")，需注册 MapAccessor（bean 属性仍走反射）
            context.addPropertyAccessor(new MapAccessor());
            context.setRootObject(buildRoot(state));
            return Boolean.TRUE.equals(expression.getValue(context, Boolean.class));
        } catch (RuntimeException e) {
            throw new RuntimeException("条件求值失败：" + condition + "：" + e.getMessage(), e);
        }
    }

    /**
     * 把所有 {@code {{path}}} 占位符替换为内部路径（剥掉花括号）。
     */
    private static String translate(String condition) {
        return condition.replaceAll(PLACEHOLDER.pattern(), "$1");
    }

    private static Map<String, Object> buildRoot(WorkflowState state) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("inputs", state.getInputs());
        root.put("nodes", state.getNodeOutputs());
        return root;
    }
}
