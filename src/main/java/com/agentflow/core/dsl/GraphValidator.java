package com.agentflow.core.dsl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.agentflow.core.model.EdgeDefinition;
import com.agentflow.core.model.EdgeType;
import com.agentflow.core.model.NodeDefinition;
import com.agentflow.core.model.NodeType;
import com.agentflow.core.model.WorkflowDefinition;
import org.springframework.stereotype.Component;

/**
 * 图定义校验器——只做"结构层"校验（见 QA 27：结构层 vs 执行层）。
 *
 * <p>校验项（消息格式对齐 DSL使用说明 7 常见错误）：
 * ① 有且仅有一个 START / END；
 * ② 工作流 / 节点 id 字符集（DSL使用说明 2.1）；
 * ③ 节点 config 字段白名单（3.3）+ outputSchema 仅 LLM / AGENTIC_LOOP 可带（3.1）；
 * ④ 边引用节点存在、不指向 START、不从 END 出发（4.1）；
 * ⑤ 边类型白名单：STATIC 不带 condition/label/description，CONDITIONAL 不带 label/description，LLM_DYNAMIC 不带 condition（4.2）；
 * ⑥ CONDITIONAL 边 condition 非空 + {{}} 成对合法（SpEL 完整语法校验在 P2，见 QA 08）；
 * ⑦ LLM_DYNAMIC 边 label 必填（4.2）；
 * ⑧ 无环——Kahn 拓扑排序，失败时反推一条环路径（对标 Spark DAG 判环）。
 *
 * <p>聚合报错：一次返回全部错误列表（空列表 = 通过），API 层可结构化展示。
 * 执行层校验（model/tool/query 必填、config 值类型、节点可达性）不在本类，见 QA 27。
 */
@Component
public class GraphValidator {

    /**
     * 节点 config 字段白名单（DSL使用说明 3.3）。
     */
    private static final Map<NodeType, Set<String>> NODE_CONFIG_WHITELIST = Map.of(
            NodeType.START, Set.of(),
            NodeType.END, Set.of(),
            NodeType.LLM, Set.of("model", "prompt", "retryPolicy"),
            NodeType.AGENTIC_LOOP, Set.of("model", "systemPrompt", "tools", "maxIterations"),
            NodeType.TOOL, Set.of("tool", "inputs", "retryPolicy"),
            NodeType.RAG, Set.of("query", "topK", "collection"),
            NodeType.MEMORY_WRITE, Set.of("user", "assistant"));

    /**
     * 允许带 outputSchema 的节点类型（DSL使用说明 3.1）。
     */
    private static final Set<NodeType> OUTPUT_SCHEMA_ALLOWED = Set.of(NodeType.LLM, NodeType.AGENTIC_LOOP);

    /**
     * id 字符集：非空、字母数字开头、只能字母数字/下划线/连字符（DSL使用说明 2.1）。
     */
    private static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9-_]*");

    /**
     * 校验工作流定义，返回全部错误（空列表 = 通过）。
     */
    public List<String> validate(WorkflowDefinition wf) {
        List<String> errors = new ArrayList<>();
        validateIds(wf, errors);
        validateNodeCounts(wf, errors);
        validateNodes(wf, errors);
        validateEdges(wf, errors);
        validateAcyclic(wf, errors);
        return errors;
    }

    private void validateIds(WorkflowDefinition wf, List<String> errors) {
        if (wf.getId() == null || wf.getId().isBlank()) {
            errors.add("工作流缺少 id");
        } else if (!ID_PATTERN.matcher(wf.getId()).matches()) {
            errors.add("工作流 id 非法：" + wf.getId());
        }
        wf.getNodes().keySet().forEach(id -> {
            if (id == null || !ID_PATTERN.matcher(id).matches()) {
                errors.add("节点 id 非法：" + id);
            }
        });
    }

    private void validateNodeCounts(WorkflowDefinition wf, List<String> errors) {
        long starts = wf.getNodes().values().stream()
                .filter(n -> n.getType() == NodeType.START).count();
        if (starts != 1) {
            errors.add("必须且仅有一个 START（当前 " + starts + " 个）");
        }
        long ends = wf.getNodes().values().stream()
                .filter(n -> n.getType() == NodeType.END).count();
        if (ends != 1) {
            errors.add("必须且仅有一个 END（当前 " + ends + " 个）");
        }
    }

    private void validateNodes(WorkflowDefinition wf, List<String> errors) {
        wf.getNodes().forEach((id, node) -> {
            if (node.getType() == null) {
                errors.add("节点 " + id + " 缺少 type");
                return;
            }
            Set<String> allowed = NODE_CONFIG_WHITELIST.get(node.getType());
            node.getConfig().keySet().forEach(key -> {
                if (!allowed.contains(key)) {
                    errors.add(node.getType() + " 节点不支持字段 " + key);
                }
            });
            if (node.getOutputSchema() != null && !OUTPUT_SCHEMA_ALLOWED.contains(node.getType())) {
                errors.add(node.getType() + " 节点不支持字段 outputSchema");
            }
        });
    }

    private void validateEdges(WorkflowDefinition wf, List<String> errors) {
        Map<String, NodeDefinition> nodes = wf.getNodes();
        for (EdgeDefinition edge : wf.getEdges()) {
            String from = edge.getFrom();
            String to = edge.getTo();
            if (from == null || from.isBlank()) {
                errors.add("边缺少 from");
            }
            if (to == null || to.isBlank()) {
                errors.add("边缺少 to");
            }
            if (from != null && !nodes.containsKey(from)) {
                errors.add("边 " + from + "→" + to + " 引用了不存在的节点 " + from);
            }
            if (to != null && !nodes.containsKey(to)) {
                errors.add("边 " + from + "→" + to + " 引用了不存在的节点 " + to);
            }
            NodeDefinition fromNode = nodes.get(from);
            NodeDefinition toNode = nodes.get(to);
            if (fromNode != null && fromNode.getType() == NodeType.END) {
                errors.add("END 不能有出边（" + from + "→" + to + "）");
            }
            if (toNode != null && toNode.getType() == NodeType.START) {
                errors.add("START 不能有入边（" + from + "→" + to + "）");
            }
            validateEdgeType(edge, errors);
        }
    }

    private void validateEdgeType(EdgeDefinition edge, List<String> errors) {
        switch (edge.getType()) {
            case EdgeType.STATIC -> {
                if (edge.getCondition() != null) errors.add("STATIC 边不支持字段 condition");
                if (edge.getLabel() != null) errors.add("STATIC 边不支持字段 label");
                if (edge.getDescription() != null) errors.add("STATIC 边不支持字段 description");
            }
            case EdgeType.CONDITIONAL -> {
                if (edge.getCondition() == null || edge.getCondition().isBlank()) {
                    errors.add("CONDITIONAL 边缺少 condition");
                } else if (!balancedBraces(edge.getCondition())) {
                    errors.add("condition 结构非法：" + edge.getCondition());
                }
                if (edge.getLabel() != null) errors.add("CONDITIONAL 边不支持字段 label");
                if (edge.getDescription() != null) errors.add("CONDITIONAL 边不支持字段 description");
            }
            case EdgeType.LLM_DYNAMIC -> {
                if (edge.getLabel() == null || edge.getLabel().isBlank()) {
                    errors.add("LLM_DYNAMIC 边缺少 label");
                }
                if (edge.getCondition() != null) errors.add("LLM_DYNAMIC 边不支持字段 condition");
            }
        }
    }

    /**
     * {{}} 结构校验：花括号成对闭合（SpEL 完整语法校验在 P2 与求值器一起做，见 QA 08）。
     */
    private static boolean balancedBraces(String expr) {
        int depth = 0;
        for (char c : expr.toCharArray()) {
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    /**
     * Kahn 拓扑排序判环；失败时反推一条环路径，消息形如
     * {@code 图存在环，拓扑排序失败，环内节点：a → b → a}。
     */
    private void validateAcyclic(WorkflowDefinition wf, List<String> errors) {
        Set<String> nodeIds = wf.getNodes().keySet();
        Map<String, List<String>> out = new HashMap<>();
        Map<String, List<String>> pred = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (String id : nodeIds) {
            out.put(id, new ArrayList<>());
            pred.put(id, new ArrayList<>());
            indegree.put(id, 0);
        }
        for (EdgeDefinition edge : wf.getEdges()) {
            String from = edge.getFrom();
            String to = edge.getTo();
            if (from == null || to == null || !nodeIds.contains(from) || !nodeIds.contains(to)) {
                continue; // 引用问题已在 validateEdges 报出，判环只看有效边
            }
            out.get(from).add(to);
            pred.get(to).add(from);
            indegree.merge(to, 1, Integer::sum);
        }

        Queue<String> queue = new ArrayDeque<>();
        indegree.forEach((id, d) -> {
            if (d == 0) {
                queue.add(id);
            }
        });

        Set<String> processed = new HashSet<>();
        while (!queue.isEmpty()) {
            String n = queue.poll();
            processed.add(n);
            for (String next : out.get(n)) {
                int d = indegree.get(next) - 1;
                indegree.put(next, d);
                if (d == 0) {
                    queue.add(next);
                }
            }
        }

        if (processed.size() < nodeIds.size()) {
            errors.add("图存在环，拓扑排序失败，环内节点：" + findCyclePath(nodeIds, processed, pred));
        }
    }

    /**
     * 在 Kahn 未处理的节点里找一条环路径。
     *
     * <p>剩余节点必有指向剩余节点的前驱（否则会被 Kahn 处理），沿前驱回走必然重复，重复点即环的起点。
     * 但前驱路径是<b>逆边方向</b>收集的，需 {@link Collections#reverse} 后才读作沿正向边的环
     * （否则 3 节点环 a→b→c→a 会误报成 "a → c → b → a"，其中 a→c 并不存在）。
     */
    private static String findCyclePath(Set<String> nodeIds, Set<String> processed, Map<String, List<String>> pred) {
        Set<String> remaining = new TreeSet<>(nodeIds);
        remaining.removeAll(processed);

        String start = remaining.iterator().next();
        List<String> path = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cur = start;
        while (seen.add(cur)) {
            path.add(cur);
            String next = pred.get(cur).stream().filter(remaining::contains).findFirst().orElse(null);
            if (next == null) {
                break;
            }
            cur = next;
        }
        int idx = path.indexOf(cur); // cur 重复出现 → 环从该位置开始
        List<String> cycle = new ArrayList<>(path.subList(idx, path.size()));
        Collections.reverse(cycle); // 前驱回溯是逆边方向，反转后读作沿正向边的环
        cycle.add(cycle.get(0)); // 回到起点，闭环显示
        return String.join(" → ", cycle);
    }
}
