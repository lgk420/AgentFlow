package com.agentflow.tool.builtin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5.4 workout_metrics 单元测试（纯计算，setsDetail 明细输入）。
 *
 * <p>覆盖：总容量（吨）、Epley 1RM（各组最大）、真实 CV（组重量变异系数，<2 组 null）、
 * 环比涨幅（对上一次训练——history 第二条，不足两条为 null）、volumeDropAlert 硬阈值、
 * comfortZoneWarning 读上一次训练 totalCv。
 */
class WorkoutMetricsToolTest {

    private final WorkoutMetricsTool tool = new WorkoutMetricsTool();

    /** 本次动作：哑铃划船 4 组 × 12 次 × 15kg → 720kg = 0.72 吨，重量恒定 → cv 0。 */
    private static final List<Map<String, Object>> ACTIONS = List.of(Map.of(
            "exercise", "哑铃划船",
            "setsDetail", List.of(
                    Map.of("weight", 15, "reps", 12),
                    Map.of("weight", 15, "reps", 12),
                    Map.of("weight", 15, "reps", 12),
                    Map.of("weight", 15, "reps", 12))));

    private static Map<String, Object> set(double weight, int reps) {
        return Map.of("weight", weight, "reps", reps);
    }

    private static Map<String, Object> action(String exercise, List<Map<String, Object>> setsDetail) {
        return Map.of("exercise", exercise, "setsDetail", setsDetail);
    }

    /** history 一条（当前会话）：newest=current，不足两条 → 无上一次基准。 */
    private static Map<String, Object> currentDay(double totalVolumeKg) {
        return Map.of("date", "20260812", "muscleGroup", "背", "totalVolumeKg", totalVolumeKg);
    }

    private static Map<String, Object> prevDay(double totalVolumeKg) {
        return Map.of("date", "20260810", "muscleGroup", "胸", "totalVolumeKg", totalVolumeKg);
    }

    private static Map<String, Object> prevDayWithCv(double totalVolumeKg, double cv) {
        Map<String, Object> m = new LinkedHashMap<>(prevDay(totalVolumeKg));
        m.put("totalCv", cv);
        return m;
    }

    @Test
    void totalVolume_inTons_andChangePctNullWithoutHistory() {
        Map<String, Object> r = tool.invoke(ACTIONS, List.of());
        assertThat(((Number) r.get("totalVolume")).doubleValue()).isEqualTo(0.72);
        assertThat(r.get("changePct")).isNull();
        assertThat(r.get("volumeDropAlert")).isEqualTo(false);
        assertThat(r.get("comfortZoneWarning")).isEqualTo(false);
    }

    @Test
    void constantWeight_sets_givesCvZeroAndTotalCv() {
        Map<String, Object> r = tool.invoke(ACTIONS, List.of());
        assertThat(r.get("totalCv")).isEqualTo(0.0); // 重量恒定 → CV 0
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) ((List<?>) r.get("actions")).get(0);
        assertThat(a.get("sets")).isEqualTo(4);
        assertThat(a.get("cv")).isEqualTo(0.0);
        assertThat(a.get("volumeKg")).isEqualTo(720.0);
    }

    @Test
    void e1RM_fromBestSet_andCvNullForSingleSet() {
        Map<String, Object> r = tool.invoke(
                List.of(action("杠铃卧推", List.of(set(30, 12)))), List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) ((List<?>) r.get("actions")).get(0);
        // Epley: 30 * (1 + 12/30) = 42
        assertThat(((Number) a.get("e1RM")).doubleValue())
                .isCloseTo(42.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(a.get("cv")).isNull(); // 1 组算不出变异系数
        assertThat(r.get("totalCv")).isNull();
    }

    @Test
    void cv_computedFromVaryingWeights() {
        // 坐姿钢线划船：16/16/16/20 → mean 17、std √3≈1.732 → cv ≈ 10.19%
        List<Map<String, Object>> varying = List.of(action("坐姿钢线划船", List.of(
                set(16, 15), set(16, 12), set(16, 10), set(20, 6))));
        Map<String, Object> r = tool.invoke(varying, List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) ((List<?>) r.get("actions")).get(0);
        assertThat(((Number) a.get("cv")).doubleValue())
                .isCloseTo(10.19, org.assertj.core.data.Offset.offset(0.01));
        assertThat(((Number) r.get("totalCv")).doubleValue())
                .isCloseTo(10.19, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void changePct_againstPreviousSession() {
        // 上一次 500kg，本次 720kg → +44%
        Map<String, Object> r = tool.invoke(ACTIONS, List.of(currentDay(720), prevDay(500)));
        assertThat(((Number) r.get("changePct")).doubleValue()).isEqualTo(44.0);
        assertThat(r.get("volumeDropAlert")).isEqualTo(false);
    }

    @Test
    void volumeDropAlert_triggeredWhenDropAtOrAbove10Percent() {
        // 上一次 1000kg，本次 720kg → -28%
        Map<String, Object> r = tool.invoke(ACTIONS, List.of(currentDay(720), prevDay(1000)));
        assertThat(((Number) r.get("changePct")).doubleValue()).isEqualTo(-28.0);
        assertThat(r.get("volumeDropAlert")).isEqualTo(true);
    }

    @Test
    void volumeDropAlert_notTriggeredWhenVolumeUp() {
        Map<String, Object> r = tool.invoke(ACTIONS, List.of(currentDay(720), prevDay(700)));
        assertThat(((Number) r.get("changePct")).doubleValue())
                .isCloseTo(2.86, org.assertj.core.data.Offset.offset(0.01));
        assertThat(r.get("volumeDropAlert")).isEqualTo(false);
    }

    @Test
    void comfortZoneWarning_requiresPreviousTotalCv() {
        // 上一次无 totalCv → 即使近乎持平也不警告
        Map<String, Object> r = tool.invoke(ACTIONS, List.of(currentDay(720), prevDay(720)));
        assertThat(((Number) r.get("changePct")).doubleValue()).isZero();
        assertThat(r.get("comfortZoneWarning")).isEqualTo(false);
    }

    @Test
    void comfortZoneWarning_whenPreviousLowCvAndFlatVolume() {
        // 上一次 low CV + 近乎持平 → 舒适区警告
        Map<String, Object> r = tool.invoke(ACTIONS, List.of(currentDay(720), prevDayWithCv(720, 3.0)));
        assertThat(r.get("comfortZoneWarning")).isEqualTo(true);
    }

    @Test
    void changePct_nullWhenNoPreviousSession() {
        assertThat(tool.invoke(ACTIONS, List.of()).get("changePct")).isNull();
        assertThat(tool.invoke(ACTIONS, List.of(currentDay(720))).get("changePct")).isNull();
        assertThat(tool.invoke(ACTIONS, List.of(currentDay(720), prevDay(0))).get("changePct")).isNull();
    }
}
