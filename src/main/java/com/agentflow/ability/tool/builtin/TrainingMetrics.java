package com.agentflow.ability.tool.builtin;

import java.util.List;
import java.util.Map;

/**
 * 训练指标共享计算（T5.4 扩展）——包内工具类，{@link WorkoutMetricsTool} 与 {@link TrainingHistoryQueryTool} 共用。
 *
 * <p>动作数据契约：{@code {exercise, setsDetail:[{weight, reps}, ...]}}（parse 后只保留 setsDetail，
 * 组数 / 重量 / 次数一律从明细派生，避免 LLM 平铺字段与明细不一致）。
 *
 * <p>CV 按设计文档公式（QA 53）：{@code std(每组重量) / mean(每组重量) × 100%}；
 * 不足 2 组算不出方差 → null。当日 {@code totalCv} = 各动作 CV 的均值（全为 null → null）。
 */
final class TrainingMetrics {

    private TrainingMetrics() {
    }

    /**
     * 所有动作总容量（kg）：Σ(每组 weight×reps)。
     */
    static double totalVolumeKg(List<Map<String, Object>> actions) {
        double sum = 0;
        for (Map<String, Object> a : actions) {
            sum += actionVolumeKg(a);
        }
        return sum;
    }

    /**
     * 单动作组数（从 setsDetail 派生）。
     */
    static int setsOf(Map<String, Object> action) {
        return sets(action).size();
    }

    /**
     * 单动作容量（kg）：Σ(每组 weight×reps)。
     */
    static double actionVolumeKg(Map<String, Object> action) {
        double sum = 0;
        for (Map<String, Object> set : sets(action)) {
            sum += num(set, "weight") * intNum(set, "reps");
        }
        return sum;
    }

    /**
     * Epley 1RM：各组 {weight × (1 + reps/30)} 中的最大值（取最重/最佳一组，多重量动作才有意义）。
     */
    static double e1RM(Map<String, Object> action) {
        double best = 0;
        for (Map<String, Object> set : sets(action)) {
            double w = num(set, "weight");
            int r = intNum(set, "reps");
            best = Math.max(best, w * (1 + r / 30.0));
        }
        return best;
    }

    /**
     * 单动作组间变异系数（%）：std(每组重量)/mean(每组重量)×100；不足 2 组 → null。
     */
    static Double actionCv(Map<String, Object> action) {
        List<Map<String, Object>> sets = sets(action);
        if (sets.size() < 2) {
            return null;
        }
        double[] weights = new double[sets.size()];
        double mean = 0;
        for (int i = 0; i < sets.size(); i++) {
            weights[i] = num(sets.get(i), "weight");
            mean += weights[i];
        }
        mean /= sets.size();
        if (mean == 0) {
            return null;
        }
        double var = 0;
        for (double w : weights) {
            var += (w - mean) * (w - mean);
        }
        var /= sets.size();
        return round(Math.sqrt(var) / mean * 100);
    }

    /**
     * 当日总 CV（%）：各动作 CV 的均值；无任何动作有 CV → null。
     */
    static Double dayTotalCv(List<Map<String, Object>> actions) {
        double sum = 0;
        int n = 0;
        for (Map<String, Object> a : actions) {
            Double cv = actionCv(a);
            if (cv != null) {
                sum += cv;
                n++;
            }
        }
        return n == 0 ? null : round(sum / n);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sets(Map<String, Object> action) {
        Object o = action.get("setsDetail");
        return o instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static int intNum(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
    }

    private static double num(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString());
    }

    static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
