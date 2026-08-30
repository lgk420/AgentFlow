package com.agentflow.tool.builtin;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

/**
 * T5.4 training_history_query 集成测试（Testcontainers redis）。
 *
 * <p>覆盖：近 N 周窗口过滤、近 2 周 accessoryHistory、日期降序、actions 保留与 totalVolumeKg 计算、
 * 空历史返回空列表。固定 today 注入保证确定性。
 */
@Testcontainers
class TrainingHistoryQueryToolTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    /** 固定"今天"=2026-08-13：近4周 cutoff=20260716、近2周 cutoff=20260730。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 13);

    private TrainingLogStoreTool store;
    private TrainingHistoryQueryTool query;

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
        store = new TrainingLogStoreTool(template);
        query = new TrainingHistoryQueryTool(template);
    }

    private static Map<String, Object> log(String date, String muscleGroup) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date);
        m.put("muscleGroup", muscleGroup);
        m.put("actions", List.of(Map.of(
                "exercise", "哑铃划船",
                "setsDetail", List.of(
                        Map.of("weight", 15, "reps", 12),
                        Map.of("weight", 15, "reps", 12),
                        Map.of("weight", 15, "reps", 12),
                        Map.of("weight", 15, "reps", 12)))));
        return m;
    }

    @Test
    void windows_filteredByWeeks_andSortedDesc() {
        store.invoke(log("20260720", "腿"), "u"); // 24 天前：近4周内、近2周外
        store.invoke(log("20260810", "背"), "u"); // 3 天前：近2周内
        store.invoke(log("20260812", "胸"), "u"); // 1 天前

        Map<String, Object> h = query.queryForUser("u", "腿", 4, TODAY);

        assertThat(h.get("recentActions")).asList().hasSize(3);
        assertThat(h.get("accessoryHistory")).asList().hasSize(2);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recent = (List<Map<String, Object>>) h.get("recentActions");
        assertThat(recent.get(0).get("date")).isEqualTo("20260812"); // 日期降序
        assertThat(recent.get(1).get("date")).isEqualTo("20260810");
        assertThat(recent.get(2).get("date")).isEqualTo("20260720");
    }

    @Test
    void emptyHistory_returnsEmptyLists() {
        Map<String, Object> h = query.queryForUser("u", "腿", 4, TODAY);
        assertThat(h.get("recentActions")).asList().isEmpty();
        assertThat(h.get("accessoryHistory")).asList().isEmpty();
    }

    @Test
    void actionsPreserved_andTotalVolumeComputed() {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("date", "20260812");
        log.put("muscleGroup", "背");
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
        store.invoke(log, "u");

        Map<String, Object> h = query.queryForUser("u", "背", 4, TODAY);
        @SuppressWarnings("unchecked")
        Map<String, Object> day = (Map<String, Object>) ((List<?>) h.get("recentActions")).get(0);

        assertThat(day.get("actions")).asList().hasSize(2);
        // 15*12*4 + 20*10*3 = 720 + 600 = 1320kg
        assertThat(((Number) day.get("totalVolumeKg")).doubleValue()).isEqualTo(1320.0);
        // 两组重量恒定 → totalCv 0
        assertThat(((Number) day.get("totalCv")).doubleValue()).isEqualTo(0.0);
    }
}
