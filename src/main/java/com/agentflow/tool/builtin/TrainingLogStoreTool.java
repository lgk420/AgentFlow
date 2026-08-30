package com.agentflow.tool.builtin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.agentflow.tool.annotation.AgentTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 训练日志存储工具（T5.4）——写入 {@code log:{userId}:{date}} Hash，并校验 腿→胸→背 轮换顺序。
 *
 * <p><b>存储模型</b>（与 {@link TrainingHistoryQueryTool} 配套，同一份键约定）：
 * <ul>
 *   <li>Hash {@code log:{userId}:{date}}：标量字段 {@code date}/{@code muscleGroup} 供轮换校验快速取，
 *       整份 log 存 {@code json}（JSON 字符串，保住 actions 数组——Hash 值必须是字符串）；</li>
 *   <li>ZSet {@code history:{userId}}：member=date、score=数值 YYYYMMDD（定宽，数值即时间序），
 *       历史查询按 score 范围取近 N 周。</li>
 * </ul>
 *
 * <p><b>轮换校验（严格循环）</b>：上次肌群必须是环中今天的上一个（腿→胸、胸→背、背→腿），
 * 重复 / 跳跃都报 {@code sequenceOk:false} + {@code violation} 说明；首次记录 / 上次肌群无法识别则放行。
 * 同日重复录入不计数为一次轮换（按严格早于今天的上一条记录判定）。
 */
@Component
public class TrainingLogStoreTool {

    /**
     * 三大肌群固定循环：上一次肌群 → 下一次肌群（腿→胸→背→腿）。
     */
    private static final Map<String, String> NEXT_GROUP = Map.of("腿", "胸", "胸", "背", "背", "腿");
    private static final Set<String> MUSCLE_GROUPS = NEXT_GROUP.keySet();
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{8}");

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public TrainingLogStoreTool(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @AgentTool(name = "training_log_store", description = "存储每日训练日志并校验肌群轮换顺序（腿→胸→背），返回是否违规")
    @SuppressWarnings("unchecked")
    public Map<String, Object> invoke(Map<String, Object> log, String userId) {
        String date = getString(log, "date");
        String muscleGroup = getString(log, "muscleGroup");
        if (!DATE_PATTERN.matcher(date).matches()) {
            throw new IllegalArgumentException("训练日志缺少合法 date（YYYYMMDD 8 位数字）：" + date);
        }
        if (!MUSCLE_GROUPS.contains(muscleGroup)) {
            throw new IllegalArgumentException("训练日志缺少合法 muscleGroup（腿/胸/背）：" + muscleGroup);
        }

        String hashKey = "log:" + userId + ":" + date;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("date", date);
        fields.put("muscleGroup", muscleGroup);
        fields.put("json", writeJson(log));
        redis.opsForHash().putAll(hashKey, fields);

        redis.opsForZSet().add("history:" + userId, date, toDateNum(date));

        String lastGroup = previousMuscleGroup(userId, date);
        return rotationResult(muscleGroup, lastGroup);
    }

    /**
     * 轮换校验结果：{@code {stored, sequenceOk, lastMuscleGroup, violation}}。
     */
    private Map<String, Object> rotationResult(String todayGroup, String lastGroup) {
        boolean sequenceOk;
        String violation = null;
        if (lastGroup == null) {
            sequenceOk = true; // 首次记录
        } else {
            String expected = NEXT_GROUP.get(lastGroup);
            if (expected == null) {
                sequenceOk = true; // 上次肌群无法识别（非腿/胸/背），无法校验
            } else if (expected.equals(todayGroup)) {
                sequenceOk = true;
            } else {
                sequenceOk = false;
                violation = "肌群轮换违规：上次「" + lastGroup + "」今天「" + todayGroup + "」，"
                        + "应按 腿→胸→背 轮到「" + expected + "」";
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stored", true);
        result.put("sequenceOk", sequenceOk);
        result.put("lastMuscleGroup", lastGroup);
        result.put("violation", violation);
        return result;
    }

    /**
     * 严格早于今天的最近一条记录的肌群（同日重复录入不计为轮换一步）。
     */
    private String previousMuscleGroup(String userId, String date) {
        Set<String> prev = redis.opsForZSet()
                .reverseRangeByScore("history:" + userId, 0, toDateNum(date) - 1, 0, 1);
        if (prev == null || prev.isEmpty()) {
            return null;
        }
        Object group = redis.opsForHash().get("log:" + userId + ":" + prev.iterator().next(), "muscleGroup");
        return group == null ? null : group.toString();
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("训练日志序列化失败", e);
        }
    }

    private static long toDateNum(String date) {
        return Long.parseLong(date);
    }

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }
}
