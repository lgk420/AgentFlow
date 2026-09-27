package com.agentflow.ability.tool.scenarios.fitness;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.agentflow.ability.tool.annotation.ToolMethod;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 健身教练助手的工具集（T5.4）——**一个场景一个类**：3 个工具 + 共享计算。
 *
 * <p>三个工具是一条链（工作流顺序 store → history_query → metrics），共用同一份 Redis 键约定
 * （见 {@link #storeLog}）与同一套计算（见类末的静态方法）。
 * 它们靠 {@link ToolMethod#name()} 区分工具名，Java 方法名只给本类看。
 */
@Component
public class FitnessTools {

    /**
     * 与 DSL store 节点的 {@code {{inputs.userId | 'default-user'}}} 对齐。
     */
    public static final String DEFAULT_USER_ID = "default-user";

    /**
     * 三大肌群固定循环：腿→胸→背→腿。
     */
    private static final Map<String, String> NEXT_GROUP = Map.of("腿", "胸", "胸", "背", "背", "腿");
    private static final Set<String> MUSCLE_GROUPS = NEXT_GROUP.keySet();
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{8}");

    /**
     * accessoryHistory 固定近 2 周（大带小参考近史）。
     */
    private static final int ACCESSORY_WEEKS = 2;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * volumeDropAlert 阈值：较上次跌幅 ≥10%。
     */
    private static final double VOLUME_DROP_THRESHOLD = -10.0;

    /**
     * comfortZoneWarning：上次低 CV（<5%）且本期容量近乎持平（|环比|≤2%）。
     */
    private static final double COMFORT_ZONE_CV_THRESHOLD = 5.0;
    private static final double COMFORT_ZONE_RANGE = 2.0;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public FitnessTools(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ═══════════════════ 工具 1：训练日志存储 ═══════════════════

    /**
     * 写 {@code log:{userId}:{date}} Hash，并校验 腿→胸→背 轮换顺序。
     *
     * <p>键约定（与 history_query 配套）：Hash {@code log:{userId}:{date}} 存 date / muscleGroup / json；
     * ZSet {@code history:{userId}} 的 member=date、score=YYYYMMDD 数值，供按时间范围取近 N 周。
     *
     * <p>轮换校验是<b>严格循环</b>：上次肌群必须是环中今天的上一个（腿→胸、胸→背、背→腿），
     * 重复 / 跳跃报 {@code sequenceOk:false} + {@code violation}；首次记录或上次肌群无法识别则放行。
     * 同日重复录入不计数（按严格早于今天的上一条判定）。
     */
    @ToolMethod(name = "training_log_store", description = "存储每日训练日志并校验肌群轮换顺序（腿→胸→背），返回是否违规")
    @SuppressWarnings("unchecked")
    public Map<String, Object> storeLog(Map<String, Object> log, String userId) {
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
     * {@code {stored, sequenceOk, lastMuscleGroup, violation}}。
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
     * 严格早于今天的最近一条记录的肌群；同日重复录入不计为轮换一步。
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

    // ═══════════════════ 工具 2：历史检索 ═══════════════════

    /**
     * 查用户训练记录，与 training_log_store 共用键约定。
     *
     * <p>返回两份：{@code recentActions}（近 N 周全记录，喂 workout_metrics 与 LLM）、
     * {@code accessoryHistory}（近 2 周，供「大带小」参考）。
     *
     * <p>userId 固定 {@value #DEFAULT_USER_ID}（与 DSL store 节点默认值一致）。窗口按「今天」往回算，
     * {@link #queryForUser} 注入 today 便于测试。
     */
    @ToolMethod(name = "training_history_query",
            description = "查询用户近 N 周训练记录：recentActions 近 N 周全记录，accessoryHistory 近 2 周记录")
    @SuppressWarnings("unchecked")
    public Map<String, Object> queryHistory(String muscle_group, Integer weeks) {
        int n = weeks != null ? weeks : 6;
        return queryForUser(DEFAULT_USER_ID, muscle_group, n, LocalDate.now());
    }

    /**
     * 内部查询入口：today 由调用方给，测试才能确定性（不用 Date.now）。
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
     * 取 [cutoff, +∞) 内最近 maxCount 条（日期降序）。
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
            day.put("totalVolumeKg", round(totalVolumeKg(actions == null ? List.of() : actions)));
            day.put("totalCv", dayTotalCv(actions == null ? List.of() : actions));
            return day;
        } catch (JsonProcessingException e) {
            return null; // 脏数据跳过，不阻断整次查询
        }
    }

    private static long toDateNum(LocalDate date) {
        return Long.parseLong(date.format(DATE_FMT));
    }

    // ═══════════════════ 工具 3：指标计算 ═══════════════════

    /**
     * 纯计算，不依赖存储。
     *
     * <p>输入：{@code actions}（本次 parse 输出，含每组明细）、{@code history}（history_query 的
     * {@code recentActions}，<b>新→旧排列，最近一条即刚入库的当前会话</b>）。
     *
     * <p>输出：总容量（吨）、{@code changePct}（对上一次训练——history 第二条，不足两条为 null）、
     * {@code totalCv}、每动作指标、{@code volumeDropAlert}、{@code comfortZoneWarning}。
     */
    @ToolMethod(name = "workout_metrics",
            description = "计算总容量(吨)、Epley 1RM、真实 CV(组重量变异系数)、环比涨幅及硬标记（容量骤降/舒适区警告）")
    public Map<String, Object> computeMetrics(List<Map<String, Object>> actions, List<Map<String, Object>> history) {
        double totalVolumeKg = totalVolumeKg(actions);

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
        result.put("totalCv", dayTotalCv(actions));
        result.put("actions", actionMetrics);
        result.put("volumeDropAlert", volumeDropAlert);
        result.put("comfortZoneWarning", comfortZoneWarning);
        return result;
    }

    private Map<String, Object> actionMetrics(Map<String, Object> action) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exercise", action.get("exercise"));
        m.put("sets", setsOf(action));
        m.put("volumeKg", round(actionVolumeKg(action)));
        m.put("e1RM", round(e1RM(action)));
        m.put("cv", actionCv(action));
        return m;
    }

    /**
     * 上一次训练日总容量。history 新→旧排列、最近一条是刚入库的当前会话，故基准取<b>第二条</b>；
     * 不足两条（首训 / 仅当前会话）→ 无基准，返回 null。
     */
    private static Double previousSessionVolumeKg(List<Map<String, Object>> history) {
        if (history == null || history.size() < 2) {
            return null;
        }
        Object v = history.get(1).get("totalVolumeKg");
        return v instanceof Number n ? n.doubleValue() : null;
    }

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

    // ═══════════════════ 共享计算 ═══════════════════

    /**
     * 动作数据契约：{@code {exercise, setsDetail:[{weight, reps}, ...]}}。
     * parse 后只保留 setsDetail，组数 / 重量 / 次数一律从明细派生，避免 LLM 平铺字段与明细不一致。
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sets(Map<String, Object> action) {
        Object o = action.get("setsDetail");
        return o instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static double totalVolumeKg(List<Map<String, Object>> actions) {
        double sum = 0;
        for (Map<String, Object> a : actions) {
            sum += actionVolumeKg(a);
        }
        return sum;
    }

    private static int setsOf(Map<String, Object> action) {
        return sets(action).size();
    }

    private static double actionVolumeKg(Map<String, Object> action) {
        double sum = 0;
        for (Map<String, Object> set : sets(action)) {
            sum += num(set, "weight") * intNum(set, "reps");
        }
        return sum;
    }

    /**
     * Epley 1RM：各组 {weight × (1 + reps/30)} 取最大。
     */
    private static double e1RM(Map<String, Object> action) {
        double best = 0;
        for (Map<String, Object> set : sets(action)) {
            double w = num(set, "weight");
            int r = intNum(set, "reps");
            best = Math.max(best, w * (1 + r / 30.0));
        }
        return best;
    }

    /**
     * 组间变异系数（%）：std(每组重量)/mean(每组重量)×100；不足 2 组算不出方差 → null。
     */
    private static Double actionCv(Map<String, Object> action) {
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
    private static Double dayTotalCv(List<Map<String, Object>> actions) {
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

    private static int intNum(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
    }

    private static double num(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString());
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
