package com.agentflow.ability.tool.builtin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentflow.ability.tool.annotation.AgentTool;
import org.springframework.stereotype.Component;

/**
 * 训练指标计算器（T5.4）——纯数学计算，不依赖外部存储。
 *
 * <p>输入契约（与 {@link TrainingHistoryQueryTool} 对齐）：
 * <ul>
 *   <li>{@code actions}：本次 parse 输出 {@code [{exercise, setsDetail:[{weight,reps}]}]}（含每组明细）；</li>
 *   <li>{@code history}：history_query 的 {@code recentActions}，每条含 {@code totalVolumeKg} 与 {@code totalCv}。
 *       <b>新→旧排列，最近一条即刚入库的当前会话</b>（工作流顺序 store → history_query 的结构事实）。</li>
 * </ul>
 *
 * <p>输出：总容量（吨）、环比涨幅（对<b>上一次训练</b>——history 第二条；不足两条即无上一次，为 null）、
 * 当日 {@code totalCv}、每动作指标（含 Epley 1RM 与真实 CV）、硬标记
 * {@code volumeDropAlert}（环比跌幅 ≥10%）与 {@code comfortZoneWarning}。
 *
 * <p><b>CV（T5.4 扩展）</b>：parse 保留每组明细后，按设计文档公式算真实 CV =
 * std(每组重量)/mean(每组重量)×100%（不足 2 组为 null）；comfortZoneWarning 读上一次训练日
 * 的 {@code totalCv}（历史记录由 history_query 产出 totalCv）。
 */
@Component
public class WorkoutMetricsTool {

    /**
     * volumeDropAlert：本次总容量较上次跌幅 ≥10%（硬阈值走代码，架构 5）。
     */
    private static final double VOLUME_DROP_THRESHOLD = -10.0;

    /**
     * comfortZoneWarning 需上一次训练低 CV（<5%）且本期容量近乎持平（|环比|≤2%）。
     */
    private static final double COMFORT_ZONE_CV_THRESHOLD = 5.0;
    private static final double COMFORT_ZONE_RANGE = 2.0;

    @AgentTool(name = "workout_metrics",
            description = "计算总容量(吨)、Epley 1RM、真实 CV(组重量变异系数)、环比涨幅及硬标记（容量骤降/舒适区警告）")
    public Map<String, Object> invoke(List<Map<String, Object>> actions, List<Map<String, Object>> history) {
        double totalVolumeKg = TrainingMetrics.totalVolumeKg(actions);

        List<Map<String, Object>> actionMetrics = new ArrayList<>();
        for (Map<String, Object> a : actions) {
            actionMetrics.add(actionMetrics(a));
        }

        Double prevVolumeKg = previousSessionVolumeKg(history);
        Double changePct = (prevVolumeKg != null && prevVolumeKg > 0)
                ? (totalVolumeKg - prevVolumeKg) / prevVolumeKg * 100
                : null;

        boolean volumeDropAlert = changePct != null && changePct <= VOLUME_DROP_THRESHOLD;
        boolean comfortZoneWarning = changePct != null && comfortZone(history, changePct);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalVolume", round(totalVolumeKg / 1000));
        result.put("changePct", changePct == null ? null : round(changePct));
        result.put("totalCv", TrainingMetrics.dayTotalCv(actions));
        result.put("actions", actionMetrics);
        result.put("volumeDropAlert", volumeDropAlert);
        result.put("comfortZoneWarning", comfortZoneWarning);
        return result;
    }

    /**
     * 单动作指标：组数从明细派生；Epley 1RM 取各组最大值；cv 为真实组间变异系数（见类注释）。
     */
    private Map<String, Object> actionMetrics(Map<String, Object> action) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exercise", action.get("exercise"));
        m.put("sets", TrainingMetrics.setsOf(action));
        m.put("volumeKg", round(TrainingMetrics.actionVolumeKg(action)));
        m.put("e1RM", round(TrainingMetrics.e1RM(action)));
        m.put("cv", TrainingMetrics.actionCv(action));
        return m;
    }

    /**
     * 上一次训练日总容量：history 新→旧排列，<b>最近一条是刚入库的当前会话</b>（store → history_query 顺序），
     * 故环比基准取第二条；不足两条（首训 / 仅当前会话）→ 无基准，返回 null。
     */
    private static Double previousSessionVolumeKg(List<Map<String, Object>> history) {
        if (history == null || history.size() < 2) {
            return null;
        }
        Object v = history.get(1).get("totalVolumeKg");
        return v instanceof Number n ? n.doubleValue() : null;
    }

    /**
     * 舒适区判定：上一次训练存在真实 CV 数据（<5%）且本期容量近乎持平。
     */
    private static boolean comfortZone(List<Map<String, Object>> history, double changePct) {
        if (history == null || history.size() < 2) {
            return false;
        }
        Object cv = history.get(1).get("totalCv");
        if (!(cv instanceof Number n)) {
            return false;
        }
        return n.doubleValue() < COMFORT_ZONE_CV_THRESHOLD && Math.abs(changePct) <= COMFORT_ZONE_RANGE;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
