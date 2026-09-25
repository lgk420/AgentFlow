package com.agentflow.ability.tool.builtin;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T5.4 training_log_store 集成测试（Testcontainers redis）。
 *
 * <p>覆盖：首条记录放行、严格循环（腿→胸→背→腿）通过 / 跳跃违规 / 重复违规、
 * 同日重复录入不构成轮换一步、非法 date/muscleGroup 明确报错、写入后能被 history_query 查回。
 */
@Testcontainers
class TrainingLogStoreToolTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

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
    void firstRecord_sequenceOkAndLastNull() {
        Map<String, Object> r = store.invoke(log("20260812", "背"), "u");
        assertThat(r.get("sequenceOk")).isEqualTo(true);
        assertThat(r.get("violation")).isNull();
        assertThat(r.get("lastMuscleGroup")).isNull();
    }

    @Test
    void correctCycle_passes() {
        store.invoke(log("20260812", "腿"), "u");
        Map<String, Object> r = store.invoke(log("20260813", "胸"), "u");
        assertThat(r.get("sequenceOk")).isEqualTo(true);
        assertThat(r.get("lastMuscleGroup")).isEqualTo("腿");
    }

    @Test
    void jump_reportsViolationWithExpected() {
        store.invoke(log("20260812", "腿"), "u");
        Map<String, Object> r = store.invoke(log("20260813", "背"), "u");
        assertThat(r.get("sequenceOk")).isEqualTo(false);
        assertThat((String) r.get("violation")).contains("轮到「胸」");
    }

    @Test
    void repeat_reportsViolation() {
        store.invoke(log("20260812", "背"), "u");
        Map<String, Object> r = store.invoke(log("20260813", "背"), "u");
        assertThat(r.get("sequenceOk")).isEqualTo(false);
    }

    @Test
    void sameDayRelogin_doesNotCountAsRotationStep() {
        store.invoke(log("20260812", "腿"), "u");
        store.invoke(log("20260812", "胸"), "u"); // 同日覆盖，不算轮换一步
        Map<String, Object> r = store.invoke(log("20260813", "背"), "u");
        // 上一条不同日期是 08-12 的「胸」，胸→背 正确
        assertThat(r.get("sequenceOk")).isEqualTo(true);
    }

    @Test
    void invalidDate_throws() {
        assertThatThrownBy(() -> store.invoke(log("2026-08-12", "背"), "u"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("date");
    }

    @Test
    void invalidMuscleGroup_throws() {
        assertThatThrownBy(() -> store.invoke(log("20260812", "肩"), "u"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("muscleGroup");
    }

    @Test
    void storedRecord_queryableByHistoryQuery() {
        store.invoke(log("20260812", "腿"), "u");
        Map<String, Object> history = query.queryForUser("u", "腿", 4, LocalDate.of(2026, 8, 13));
        assertThat(history.get("recentActions")).asList().hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> day = (Map<String, Object>) ((List<?>) history.get("recentActions")).get(0);
        assertThat(day.get("muscleGroup")).isEqualTo("腿");
        // 15kg × 4 组 × 12 次 = 720kg
        assertThat(((Number) day.get("totalVolumeKg")).doubleValue()).isEqualTo(720.0);
    }
}
