package com.agentflow.core.dsl;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 占位符模板解析器：把含 {@code {{...}}} 的模板字符串填成运行时数据。
 *
 * <p>占位符三来源（DSL使用说明 5）：{@code {{inputs.x}}}（运行输入）、{@code {{nodes.n.output.f}}}（上游节点输出，
 * 字段链式取值）、{@code {{memory.*}}}（会话记忆，T10.2 起真正可用——由调用方在拼 context 时注入，
 * 本类不关心它从哪来）。
 *
 * <p>规则：
 * ① 宽松空格：{@code {{ x }}} ≡ {@code {{x}}}（占位符前后空格 trim，段名不 trim）；
 * ② 默认值：{@code {{x | '默认'}}}（字符串）/ {@code {{n | 0}}}（数字）/ {@code {{b | true}}}（布尔）/ {@code null}；
 * ③ 缺 key 且无默认 → 抛 {@link TemplateResolutionException}（严格模式，坏模板尽早暴露）；
 * ④ 不匹配的 {@code {{}（无 {@code }}}）→ 保留原文不做校验（校验只到 T1.3 condition 浅层平衡，见 QA 30）。
 *
 * <p>上下文是嵌套 Map（顶层命名空间 inputs / nodes / memory），P2 执行时从 WorkflowState 现拼，与状态模型解耦。
 * 字段链中间层既可以是 Map，也可以是 POJO——运行时 {@code nodes} 命名空间下存的是
 * {@code NodeOutput}（T2.1 状态模型决策），{@code {{nodes.x.output.y}}} 需经 getter（{@code getOutput()}）取字段。
 *
 * <p>两种解析入口：
 * {@link #resolve} 把模板整体转成字符串（LLM prompt / RAG query 等文本位）；
 * {@link #resolveObject} 保留原始类型（TOOL inputs，P5 补——整段 {@code {{...}}} 占位取原对象，混合文本取字符串）。
 */
@Component
public class TemplateResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]*)\\}\\}");

    /**
     * 整段即一个占位符（如 {@code "{{nodes.a.output}}"}）——typed 解析时取原对象而非字符串。
     */
    private static final Pattern SINGLE_PLACEHOLDER = Pattern.compile("^\\{\\{([^{}]*)\\}\\}$");

    private final ObjectMapper mapper;

    public TemplateResolver() {
        this(new ObjectMapper());
    }

    public TemplateResolver(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 解析模板字符串，把每个 {@code {{...}}} 占位符替换为 context 里的值。
     * 占位符解出 Map / List 时序列化为 JSON（喂 LLM 友好），标量直接 toString。
     */
    public String resolve(String template, Map<String, Object> context) {
        if (template == null) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object value = resolvePlaceholder(m.group(1), context);
            m.appendReplacement(sb, Matcher.quoteReplacement(display(value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 占位符值的展示形式：Map / List → JSON；其余 → toString。
     */
    private String display(Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return mapper.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("模板占位符序列化为 JSON 失败：" + value, e);
            }
        }
        return String.valueOf(value);
    }

    /**
     * 类型化解析（T5.4，TOOL 节点参数绑定）——保留值的原始类型：
     * ① 整段即一个占位符（{@code {{nodes.a.output}}} / {@code {{x | 3}}}）→ 返回解析后的原对象（Map/List/标量）；
     * ② 混合文本（{@code "训练 {{x}}" }）或裸字符串 → 走 {@link #resolve} 转字符串；
     * ③ Map / List 递归解析其字符串叶子；数字 / 布尔 / null 原样返回。
     */
    public Object resolveObject(Object value, Map<String, Object> context) {
        if (value instanceof String s) {
            String trimmed = s.trim();
            Matcher m = SINGLE_PLACEHOLDER.matcher(trimmed);
            if (m.matches()) {
                return resolvePlaceholder(m.group(1), context);
            }
            return resolve(s, context);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                result.put(String.valueOf(e.getKey()), resolveObject(e.getValue(), context));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(resolveObject(item, context));
            }
            return result;
        }
        return value;
    }

    /**
     * 解析单个占位符内容（不含外层 {{}}），返回解析后的值。
     */
    private Object resolvePlaceholder(String raw, Map<String, Object> context) {
        String expr = raw.trim(); // 宽松处理 {{ }} 前后空格
        if (expr.isEmpty()) {
            throw new TemplateResolutionException("空占位符 {{}}");
        }
        int pipe = expr.indexOf('|');
        String path = (pipe >= 0 ? expr.substring(0, pipe) : expr).trim();
        String defaultLit = pipe >= 0 ? expr.substring(pipe + 1).trim() : null;

        Object value = lookup(context, path);
        if (value != null) {
            return value;
        }
        if (defaultLit != null) {
            return parseLiteral(defaultLit, expr);
        }
        throw new TemplateResolutionException("模板占位符缺少值且无默认：{{" + expr + "}}");
    }

    /**
     * 沿字段链取值：Map 按 key，POJO 按 getter（{@code output} → {@code getOutput()}）。
     * 中间层取不到（缺 key / 无 getter / 非对象）视为缺值，返回 null。
     */
    private Object lookup(Map<String, Object> context, String path) {
        // memory 命名空间由调用方（TemplateContextFactory）在拼 context 时注入，
        // 这里不再特殊处理——T10.2 之前它对 memory 直接抛「未实现」，现在与 inputs/nodes 同等对待。
        Object cur = context;
        for (String seg : path.split("\\.")) {
            if (cur instanceof Map<?, ?> m) {
                if (!m.containsKey(seg)) {
                    return null;
                }
                cur = m.get(seg);
            } else {
                cur = property(cur, seg);
                if (cur == null) {
                    return null;
                }
            }
        }
        return cur;
    }

    /**
     * POJO 属性取值：反射调 {@code getXxx()}；无 getter / 调用失败返回 null（视为缺值）。
     */
    private static Object property(Object target, String name) {
        if (name.isEmpty() || target == null) {
            return null;
        }
        try {
            String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            Method method = target.getClass().getMethod(getter);
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * 解析默认值字面量：'...' 字符串 / 数字 / 布尔 / null；其他抛错。
     */
    private Object parseLiteral(String lit, String expr) {
        if (lit.length() >= 2 && lit.startsWith("'") && lit.endsWith("'")) {
            return lit.substring(1, lit.length() - 1);
        }
        if (lit.equals("true") || lit.equals("false")) {
            return Boolean.valueOf(lit);
        }
        if (lit.equals("null")) {
            return null;
        }
        // 注意：不能用三元 cond ? Double.valueOf : Integer.valueOf——Integer/Double 会触发
        // 条件表达式的数值提升（拆箱成 int/double 再提升为 double），整型默认值会被误转成 3.0。
        try {
            if (lit.contains(".")) {
                return Double.valueOf(lit);
            }
            return Integer.valueOf(lit);
        } catch (NumberFormatException e) {
            throw new TemplateResolutionException("非法默认值字面量：" + lit + "（{{" + expr + "}}）");
        }
    }
}
