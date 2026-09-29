package com.agentflow.ability.tool.scenarios;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentflow.ability.tool.scenarios.fitness.FitnessTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T5.4 training_log_memory 集成测试（Testcontainers redis）——两个工具合并后。
 *
 * <p>覆盖：存了能读回（{@code history[0]} 就是刚存的本次）、近 N 周窗口过滤、日期降序、
 * history 每条的<b>形状</b>（容量/CV 算得出来，但明细 {@code actions} 不返回——省 prompt token）、
 * 空历史返回空列表、非法 date / 主练部位拒绝入库。
 *
 * <p>窗口类用例固定注入 asOf 保证确定性；涉及 {@code trainingLogMemory} 自身读回的用例
 * 用<b>相对今天</b>的日期（窗口锚在日志里的 date，所以那类用例得让日期落在近期）。
 */
@Testcontainers
class TrainingLogMemoryToolTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    /** 固定"今天"=2026-08-13：近 4 周 cutoff=20260716。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 13);

    private static final DateTimeFormatter FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private FitnessTools tools;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        template.execute((RedisCallback<Object>) connection -> {
            connection.flushDb();
            return null;
        });
        tools = new FitnessTools(template, null);
    }

    /** 一条最小可用的日志：日期 + 主练部位 + 一个小肌群 + 一个动作（4 组 15kg×12）。 */
    private static Map<String, Object> log(String date, String primaryMuscleGroup) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date);
        m.put("primaryMuscleGroup", primaryMuscleGroup);
        m.put("accessoryMuscleGroups", List.of("二头"));
        m.put("actions", List.of(Map.of(
                "exercise", "哑铃划船",
                "setsDetail", List.of(
                        Map.of("weight", 15, "reps", 12),
                        Map.of("weight", 15, "reps", 12),
                        Map.of("weight", 15, "reps", 12),
                        Map.of("weight", 15, "reps", 12)))));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> historyOf(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("history");
    }

    // ---------- 合并后的契约：存 + 读一次调用 ----------

    @Test
    void trainingLogMemory_storesThenReadsBack_withThisSessionFirst() {
        String today = LocalDate.now().format(FMT);
        String lastWeek = LocalDate.now().minusWeeks(1).format(FMT);

        tools.trainingLogMemory(log(lastWeek, "胸"), "u", null);
        Map<String, Object> result = tools.trainingLogMemory(log(today, "腿"), "u", null);

        List<Map<String, Object>> history = historyOf(result);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("date")).isEqualTo(today);              // 第 0 条 = 刚存的本次
        assertThat(history.get(0).get("primaryMuscleGroup")).isEqualTo("腿");
        assertThat(history.get(1).get("date")).isEqualTo(lastWeek);
        assertThat(history.get(1).get("primaryMuscleGroup")).isEqualTo("胸");
    }

    // ---------- 窗口与排序 ----------

    @Test
    void windows_filteredByWeeks_andSortedDesc() {
        tools.trainingLogMemory(log("20260720", "腿"), "u", null); // 24 天前：4 周内
        tools.trainingLogMemory(log("20260810", "背"), "u", null);
        tools.trainingLogMemory(log("20260812", "胸"), "u", null);

        List<Map<String, Object>> history = historyOf(tools.historyForUser("u", 4, TODAY));

        assertThat(history).hasSize(3);
        assertThat(history.get(0).get("date")).isEqualTo("20260812"); // 日期降序
        assertThat(history.get(1).get("date")).isEqualTo("20260810");
        assertThat(history.get(2).get("date")).isEqualTo("20260720");
    }

    @Test
    void window_excludesRecordsOlderThanCutoff() {
        tools.trainingLogMemory(log("20260601", "腿"), "u", null); // 10 周前
        tools.trainingLogMemory(log("20260810", "背"), "u", null);

        assertThat(historyOf(tools.historyForUser("u", 4, TODAY))).hasSize(1);
    }

    @Test
    void window_upperBoundIsAsOf_excludesLaterSessions() {
        tools.trainingLogMemory(log("20260808", "胸"), "u", null);
        tools.trainingLogMemory(log("20260812", "背"), "u", null);

        // 锚在 20260808：只该看到它自己，看不到之后那次——否则 history[0] 就不是「本次」了
        List<Map<String, Object>> at0808 = historyOf(tools.historyForUser("u", 4, LocalDate.of(2026, 8, 8)));
        assertThat(at0808).hasSize(1);
        assertThat(at0808.get(0).get("date")).isEqualTo("20260808");

        // 锚在 20260812：两条都在，第 0 条是它自己
        List<Map<String, Object>> at0812 = historyOf(tools.historyForUser("u", 4, LocalDate.of(2026, 8, 12)));
        assertThat(at0812).hasSize(2);
        assertThat(at0812.get(0).get("date")).isEqualTo("20260812");
    }

    @Test
    void emptyHistory_returnsEmptyList() {
        assertThat(historyOf(tools.historyForUser("u", 4, TODAY))).isEmpty();
    }

    // ---------- 每条记录的形状 ----------

    @Test
    void entryShape_hasVolumeAndCv_butNoActionsDetail() {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("date", "20260812");
        log.put("primaryMuscleGroup", "背");
        log.put("accessoryMuscleGroups", List.of("二头", "三角后束"));
        log.put("actions", List.of(
                Map.of("exercise", "哑铃划船",
                        "setsDetail", List.of(
                                Map.of("weight", 15, "reps", 12),
                                Map.of("weight", 15, "reps", 12),
                                Map.of("weight", 15, "reps", 12),
                                Map.of("weight", 15, "reps", 12))),
                Map.of("exercise", "钢线下拉",
                        "setsDetail", List.of(
                                Map.of("weight", 20, "reps", 10),
                                Map.of("weight", 20, "reps", 10),
                                Map.of("weight", 20, "reps", 10)))));
        tools.trainingLogMemory(log, "u", null);

        Map<String, Object> day = historyOf(tools.historyForUser("u", 4, TODAY)).get(0);

        assertThat(day.get("primaryMuscleGroup")).isEqualTo("背");
        assertThat(day.get("accessoryMuscleGroups")).asList().containsExactly("二头", "三角后束");
        // 15*12*4 + 20*10*3 = 720 + 600 = 1320kg
        assertThat(((Number) day.get("totalVolumeKg")).doubleValue()).isEqualTo(1320.0);
        assertThat(((Number) day.get("totalCv")).doubleValue()).isEqualTo(0.0); // 两组重量恒定
        // 明细不返回：两个消费者都用不上，而 plan 节点要整份塞进 prompt
        assertThat(day).doesNotContainKey("actions");
    }

    // ---------- 入库校验 ----------

    @Test
    void dateNotEightDigits_throws() {
        assertThatThrownBy(() -> tools.trainingLogMemory(log("2026-08-12", "腿"), "u", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("date");
    }

    @Test
    void primaryMuscleGroupNotInEnum_throws() {
        assertThatThrownBy(() -> tools.trainingLogMemory(log("20260812", "腹肌"), "u", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("primaryMuscleGroup");
    }

    /** 值域已放开到 6 个部位——肩/手臂不再是"非三大肌群"就拒绝。 */
    @Test
    void nonBigMuscleGroups_areAccepted() {
        tools.trainingLogMemory(log("20260812", "肩"), "u", null);
        tools.trainingLogMemory(log("20260813", "手臂"), "u", null);

        assertThat(historyOf(tools.historyForUser("u", 4, TODAY))).hasSize(2);
    }
}
