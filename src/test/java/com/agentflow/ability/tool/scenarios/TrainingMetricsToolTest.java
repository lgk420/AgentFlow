package com.agentflow.ability.tool.scenarios;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentflow.ability.tool.scenarios.fitness.FitnessTools;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5.4 training_metrics 单元测试（纯计算，setsDetail 明细输入）。
 *
 * <p>覆盖：总容量（吨）、Epley 1RM（各组最大）、真实 CV（组重量变异系数，<2 组 null）、
 * 环比涨幅、volumeDropAlert 硬阈值、comfortZoneWarning。
 *
 * <p><b>环比基准是「上次同部位」</b>——按 腿→胸→背 轮换，{@code history[1]} 必然是另一个肌群，
 * 拿腿日跟胸日比没有意义。所以用例里的 history 都构造出<b>穿插了别的部位</b>的形状，
 * 专测"跳过第 0 条（本次）、往后找第一条同部位"这条规则。
 */
class TrainingMetricsToolTest {

    /**
     * redis 传 null——本测试只调 training_metrics，那条路径不碰存储。
     */
    private final FitnessTools tool = new FitnessTools(null, null);

    /** 本次练的部位。 */
    private static final String GROUP = "腿";

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

    /** history 一条：只有日期 / 部位 / 总容量。 */
    private static Map<String, Object> day(String date, String primaryMuscleGroup, double totalVolumeKg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date);
        m.put("primaryMuscleGroup", primaryMuscleGroup);
        m.put("totalVolumeKg", totalVolumeKg);
        return m;
    }

    private static Map<String, Object> dayWithCv(String date, String primaryMuscleGroup, double volumeKg, double cv) {
        Map<String, Object> m = day(date, primaryMuscleGroup, volumeKg);
        m.put("totalCv", cv);
        return m;
    }

    /** 本次（history[0]）——部位与 GROUP 相同，即"刚入库的这条"。 */
    private static Map<String, Object> thisSession(double totalVolumeKg) {
        return day("20260812", GROUP, totalVolumeKg);
    }

    /** 上一次训练：轮换到别的部位，**不该**当环比基准。 */
    private static Map<String, Object> lastOtherGroup(double totalVolumeKg) {
        return day("20260810", "胸", totalVolumeKg);
    }

    /** 上上次：同部位（腿），**这才是**基准。 */
    private static Map<String, Object> lastSameGroup(double totalVolumeKg) {
        return day("20260808", GROUP, totalVolumeKg);
    }

    // ---------- 基础指标 ----------

    @Test
    void totalVolume_inTons_andChangePctNullWithoutHistory() {
        Map<String, Object> r = tool.trainingMetrics(ACTIONS, List.of(), GROUP);
        assertThat(((Number) r.get("totalVolume")).doubleValue()).isEqualTo(0.72);
        assertThat(r.get("changePct")).isNull();
        assertThat(r.get("volumeDropAlert")).isEqualTo(false);
        assertThat(r.get("comfortZoneWarning")).isEqualTo(false);
    }

    @Test
    void constantWeight_sets_givesCvZeroAndTotalCv() {
        Map<String, Object> r = tool.trainingMetrics(ACTIONS, List.of(), GROUP);
        assertThat(r.get("totalCv")).isEqualTo(0.0); // 重量恒定 → CV 0
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) ((List<?>) r.get("actions")).get(0);
        assertThat(a.get("sets")).isEqualTo(4);
        assertThat(a.get("cv")).isEqualTo(0.0);
        assertThat(a.get("volumeKg")).isEqualTo(720.0);
    }

    @Test
    void e1RM_fromBestSet_andCvNullForSingleSet() {
        Map<String, Object> r = tool.trainingMetrics(
                List.of(action("杠铃卧推", List.of(set(30, 12)))), List.of(), GROUP);
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) ((List<?>) r.get("actions")).get(0);
        // Epley: 30 * (1 + 12/30) = 42
        assertThat(((Number) a.get("e1RM")).doubleValue()).isCloseTo(42.0, Offset.offset(0.01));
        assertThat(a.get("cv")).isNull(); // 1 组算不出变异系数
        assertThat(r.get("totalCv")).isNull();
    }

    @Test
    void cv_computedFromVaryingWeights() {
        // 坐姿钢线划船：16/16/16/20 → mean 17、std √3≈1.732 → cv ≈ 10.19%
        List<Map<String, Object>> varying = List.of(action("坐姿钢线划船", List.of(
                set(16, 15), set(16, 12), set(16, 10), set(20, 6))));
        Map<String, Object> r = tool.trainingMetrics(varying, List.of(), GROUP);
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) ((List<?>) r.get("actions")).get(0);
        assertThat(((Number) a.get("cv")).doubleValue()).isCloseTo(10.19, Offset.offset(0.01));
        assertThat(((Number) r.get("totalCv")).doubleValue()).isCloseTo(10.19, Offset.offset(0.01));
    }

    // ---------- 环比：跳过不同部位，找上次同部位 ----------

    @Test
    void changePct_skipsDifferentGroups_findsLastSameGroup() {
        // history: [本次 腿 720, 上次 胸 500, 上上次 腿 600] → 基准取 600（不是 500）
        Map<String, Object> r = tool.trainingMetrics(ACTIONS,
                List.of(thisSession(720), lastOtherGroup(500), lastSameGroup(600)), GROUP);

        assertThat(((Number) r.get("changePct")).doubleValue()).isEqualTo(20.0); // (720-600)/600
        assertThat(r.get("volumeDropAlert")).isEqualTo(false);
    }

    @Test
    void changePct_null_whenOnlyDifferentGroupsInHistory() {
        // 窗口里只有别的部位 → 没有可比基准，宁可 null 也不拿胸日跟腿日比
        Map<String, Object> r = tool.trainingMetrics(ACTIONS,
                List.of(thisSession(720), lastOtherGroup(500)), GROUP);

        assertThat(r.get("changePct")).isNull();
        assertThat(r.get("volumeDropAlert")).isEqualTo(false);
        assertThat(r.get("comfortZoneWarning")).isEqualTo(false);
    }

    @Test
    void volumeDropAlert_triggeredWhenDropAtOrAbove10Percent() {
        Map<String, Object> r = tool.trainingMetrics(ACTIONS,
                List.of(thisSession(720), lastSameGroup(1000)), GROUP);

        assertThat(((Number) r.get("changePct")).doubleValue()).isEqualTo(-28.0);
        assertThat(r.get("volumeDropAlert")).isEqualTo(true);
    }

    @Test
    void volumeDropAlert_notTriggeredWhenVolumeUp() {
        Map<String, Object> r = tool.trainingMetrics(ACTIONS,
                List.of(thisSession(720), lastSameGroup(700)), GROUP);

        assertThat(((Number) r.get("changePct")).doubleValue()).isCloseTo(2.86, Offset.offset(0.01));
        assertThat(r.get("volumeDropAlert")).isEqualTo(false);
    }

    // ---------- 舒适区警告 ----------

    @Test
    void comfortZoneWarning_requiresBaselineTotalCv() {
        // 基准那条没有 totalCv → 即使近乎持平也不警告
        Map<String, Object> r = tool.trainingMetrics(ACTIONS,
                List.of(thisSession(720), lastSameGroup(720)), GROUP);

        assertThat(((Number) r.get("changePct")).doubleValue()).isZero();
        assertThat(r.get("comfortZoneWarning")).isEqualTo(false);
    }

    @Test
    void comfortZoneWarning_whenBaselineLowCvAndFlatVolume() {
        Map<String, Object> r = tool.trainingMetrics(ACTIONS,
                List.of(thisSession(720), dayWithCv("20260808", GROUP, 720, 3.0)), GROUP);

        assertThat(r.get("comfortZoneWarning")).isEqualTo(true);
    }

    @Test
    void comfortZoneWarning_notTriggered_whenBaselineHasHighCv() {
        // 基准 CV 高（练得够狠）→ 不算舒适区
        Map<String, Object> r = tool.trainingMetrics(ACTIONS,
                List.of(thisSession(720), dayWithCv("20260808", GROUP, 720, 12.0)), GROUP);

        assertThat(r.get("comfortZoneWarning")).isEqualTo(false);
    }

    // ---------- 无基准的几种情形 ----------

    @Test
    void changePct_nullWhenNoBaseline() {
        assertThat(tool.trainingMetrics(ACTIONS, List.of(), GROUP).get("changePct")).isNull();
        assertThat(tool.trainingMetrics(ACTIONS, List.of(thisSession(720)), GROUP).get("changePct")).isNull();
        assertThat(tool.trainingMetrics(ACTIONS,
                List.of(thisSession(720), lastSameGroup(0)), GROUP).get("changePct")).isNull(); // 基准容量 0，除不了
    }
}
