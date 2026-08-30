package com.agentflow.tool.builtin;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.agentflow.tool.annotation.AgentTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 历史检索工具（T5.4）——从 Redis 查询用户训练记录，与 {@link TrainingLogStoreTool} 共用键约定。
 *
 * <p>返回两份（T5.4 决策）：
 * <ul>
 *   <li>{@code recentActions}：近 N 周全部训练记录（每条含 date/muscleGroup/actions/totalVolumeKg/totalCv），
 *       喂 {@link WorkoutMetricsTool} 算环比与舒适区，也喂 LLM 看趋势；</li>
 *   <li>{@code accessoryHistory}：近 2 周记录（存储只有每日主肌群、无小肌群分类，
 *       小肌群识别交给 N4 LLM 动态推理，V1 诚实简化）。</li>
 * </ul>
 *
 * <p>userId 固定为 {@value #DEFAULT_USER_ID}（DSL 的 store 节点 userId 也默认 default-user，二者一致；
 * 多用户化留后续）。查询日期窗口按「今天」往回算，{@link #queryForUser} 注入 today 便于测试。
 */
@Component
public class TrainingHistoryQueryTool {

    /**
     * 演示期固定用户：与 fitness-coast DSL store 节点的 {@code {{inputs.userId | 'default-user'}}} 对齐。
     */
    public static final String DEFAULT_USER_ID = "default-user";

    /**
     * accessoryHistory 固定近 2 周（大带小参考近史）。
     */
    private static final int ACCESSORY_WEEKS = 2;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public TrainingHistoryQueryTool(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @AgentTool(name = "training_history_query",
            description = "查询用户近 N 周训练记录：recentActions 近 N 周全记录，accessoryHistory 近 2 周记录")
    @SuppressWarnings("unchecked")
    public Map<String, Object> invoke(String muscle_group, Integer weeks) {
        int n = weeks != null ? weeks : 6;
        return queryForUser(DEFAULT_USER_ID, muscle_group, n, LocalDate.now());
    }

    /**
     * 内部查询入口：today 注入使测试确定性（不用 Date.now）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> queryForUser(String userId, String muscleGroup, int weeks, LocalDate today) {
        long cutoff = toDateNum(today.minusWeeks(weeks));
        List<Map<String, Object>> recent = loadRange(userId, cutoff, weeks * 7);
        long accCutoff = toDateNum(today.minusWeeks(ACCESSORY_WEEKS));
        List<Map<String, Object>> accessory = loadRange(userId, accCutoff, ACCESSORY_WEEKS * 7);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recentActions", recent);
        result.put("accessoryHistory", accessory);
        return result;
    }

    /**
     * 取 [cutoff, +∞) 内最近 maxCount 条记录（日期降序）。
     */
    private List<Map<String, Object>> loadRange(String userId, long cutoff, int maxCount) {
        Set<String> dates = redis.opsForZSet()
                .reverseRangeByScore("history:" + userId, cutoff, Double.MAX_VALUE, 0, maxCount);
        List<Map<String, Object>> result = new ArrayList<>();
        if (dates == null) {
            return result;
        }
        for (String date : dates) {
            Map<Object, Object> entries = redis.opsForHash().entries("log:" + userId + ":" + date);
            Object json = entries.get("json");
            if (json == null) {
                continue;
            }
            Map<String, Object> day = toDayEntry(date, json.toString());
            if (day != null) {
                result.add(day);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toDayEntry(String date, String json) {
        try {
            Map<String, Object> log = mapper.readValue(json, Map.class);
            Object actionsObj = log.get("actions");
            List<Map<String, Object>> actions = (List<Map<String, Object>>) actionsObj;
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", date);
            day.put("muscleGroup", log.get("muscleGroup"));
            day.put("actions", actions == null ? List.of() : actions);
            day.put("totalVolumeKg", TrainingMetrics.round(
                    TrainingMetrics.totalVolumeKg(actions == null ? List.of() : actions)));
            day.put("totalCv", TrainingMetrics.dayTotalCv(actions == null ? List.of() : actions));
            return day;
        } catch (JsonProcessingException e) {
            return null; // 脏数据跳过，不阻断整次查询
        }
    }

    private static long toDateNum(LocalDate date) {
        return Long.parseLong(date.format(DATE_FMT));
    }
}
