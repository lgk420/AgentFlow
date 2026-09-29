package com.agentflow.ability.tool.scenarios.fitness;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.agentflow.ability.rag.dto.RagChunk;
import com.agentflow.ability.rag.retrieval.RagRetriever;
import com.agentflow.ability.tool.annotation.ToolMethod;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 健身教练助手的工具集（T5.4）——**一个场景一个类**：3 个工具 + 共享计算。
 *
 * <p>前两个是一条链：{@link #trainingLogMemory}（存本次 + 读回全部）→ {@link #trainingMetrics}（算指标），
 * 共用同一份 Redis 键约定（见 {@link #trainingLogMemory}）与同一套计算（见类末的静态方法）。
 * 第三个 {@link #knowledgeSearch} 走知识库，跟存储无关。
 * 它们靠 {@link ToolMethod#name()} 区分工具名，Java 方法名只给本类看。
 */
@Component
public class FitnessTools {

    /**
     * 训练日志能记的部位——与知识库 {@code 01-动作要领.md} 的 {@code 肌群:} 标签一一对应，
     * 按肌群检索才对得上。其中只有 {@code 腿/胸/背} 是大肌群（进轮换），其余算小肌群。
     */
    private static final Set<String> MUSCLE_GROUPS = Set.of("腿", "胸", "背", "肩", "手臂", "功能性");

    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{8}");

    /**
     * {@link #trainingLogMemory} 不传 weeks 时的历史窗口。
     */
    private static final int DEFAULT_WEEKS = 6;

    /**
     * 知识库 collection 名（与灌库 API 用的名字一致）。
     */
    private static final String KNOWLEDGE_COLLECTION = "workout_kb";

    /**
     * {@link #knowledgeSearch} 不传 topK 时的条数。
     */
    private static final int DEFAULT_TOP_K = 3;

    /**
     * {@link #knowledgeSearch} 一条没命中时返回的话——让模型知道"没查到"，而不是拿到空串自己编。
     */
    private static final String NO_KNOWLEDGE_HIT = "（知识库里没有相关内容）";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * volumeDropAlert 阈值：较上次**同部位**跌幅 ≥10%。
     */
    private static final double VOLUME_DROP_THRESHOLD = -10.0;

    /**
     * comfortZoneWarning：上次低 CV（<5%）且本期容量近乎持平（|环比|≤2%）。
     */
    private static final double COMFORT_ZONE_CV_THRESHOLD = 5.0;
    private static final double COMFORT_ZONE_RANGE = 2.0;

    private final StringRedisTemplate redis;
    private final RagRetriever ragRetriever;
    private final ObjectMapper mapper = new ObjectMapper();

    public FitnessTools(StringRedisTemplate redis, RagRetriever ragRetriever) {
        this.redis = redis;
        this.ragRetriever = ragRetriever;
    }

    // ═══════════════════ 工具 1：训练日志存取 ═══════════════════

    /**
     * 写入本次训练日志，然后读回该用户近 N 周的全部记录。
     *
     * <p><b>为什么存和读在一个工具里</b>：两者共用同一份键约定，而且下游要的"历史"天然包含刚存的这条
     * （{@code history[0]} 就是本次）——分成两个工具只是多一次调用、多一份参数。
     *
     * <p>键约定（另一个工具 {@link #trainingMetrics} 按它读回的量算指标）：
     * Hash {@code log:{userId}:{date}} 存 date / primaryMuscleGroup / json；
     * ZSet {@code history:{userId}} 的 member=date、score=YYYYMMDD 数值，供按时间范围取近 N 周。
     *
     * <p><b>不做轮换校验</b>：这个工具只负责存取，不做业务判断——轮换规则（"最近两次不同肌群之外"）
     * 由 plan 节点按历史自己推。
     *
     * @param log    本次日志（parsing 节点的整个输出）
     * @param userId 用户标识。训练数据按它分账，与对话记忆的 sessionId 是两套
     * @param weeks  历史窗口；不传取 {@value #DEFAULT_WEEKS}
     */
    @ToolMethod(name = "training_log_memory",
            description = "记录本次训练日志，并返回该用户截至本次的近 N 周训练记录")
    @SuppressWarnings("unchecked")
    public Map<String, Object> trainingLogMemory(Map<String, Object> log, String userId, Integer weeks) {
        String date = store(log, userId);
        // 窗口锚在**这次训练那天**，不是「现在」——补录旧日志时，它自己必须落在窗口里
        return historyForUser(userId, weeks != null ? weeks : DEFAULT_WEEKS,
                LocalDate.parse(date, DATE_FMT));
    }

    /**
     * 写入 + 校验，返回归一化后的训练日期（调用方拿它锚定历史窗口）。
     *
     * <p>校验不过抛异常——宁可这次失败，也不要脏数据进库（它会污染之后所有的环比与轮换判断）。
     */
    private String store(Map<String, Object> log, String userId) {
        String date = getString(log, "date");
        String primaryMuscleGroup = getString(log, "primaryMuscleGroup");
        if (!DATE_PATTERN.matcher(date).matches()) {
            throw new IllegalArgumentException("训练日志缺少合法 date（YYYYMMDD 8 位数字）：" + date);
        }
        if (!MUSCLE_GROUPS.contains(primaryMuscleGroup)) {
            throw new IllegalArgumentException(
                    "训练日志缺少合法 primaryMuscleGroup（腿/胸/背/肩/手臂/功能性）：" + primaryMuscleGroup);
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("date", date);
        fields.put("primaryMuscleGroup", primaryMuscleGroup);
        fields.put("json", writeJson(log));
        redis.opsForHash().putAll("log:" + userId + ":" + date, fields);

        redis.opsForZSet().add("history:" + userId, date, toDateNum(date));
        return date;
    }

    /**
     * 读回**截至 asOf（含）**的最近 N 周记录，返回 {@code {history: [...]}}（日期降序）。
     *
     * <p><b>上界也得封在 asOf</b>：不封顶的话，日期排在本次之后的记录也会被拉进来，
     * 那样 {@code history[0]} 就不是本次了——而「第 0 条即本次」是整套约定的地基
     * （{@link #trainingMetrics} 的环比基准、plan 节点数「最近两次」都靠它）。
     *
     * <p>内部入口：asOf 由调用方给，测试才能确定性。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> historyForUser(String userId, int weeks, LocalDate asOf) {
        List<Map<String, Object>> history =
                loadRange(userId, toDateNum(asOf.minusWeeks(weeks)), toDateNum(asOf), weeks * 7);
        return Map.of("history", history);
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

    /**
     * 取 {@code [cutoff, maxDate]} 内最近 maxCount 条（日期降序）。上下界都是 {@code YYYYMMDD} 数值，含端点。
     */
    private List<Map<String, Object>> loadRange(String userId, long cutoff, long maxDate, int maxCount) {
        Set<String> dates = redis.opsForZSet()
                .reverseRangeByScore("history:" + userId, cutoff, maxDate, 0, maxCount);
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

    /**
     * 一条历史记录 → 下游要的形状：{@code {date, primaryMuscleGroup, accessoryMuscleGroups, totalVolumeKg, totalCv}}。
     *
     * <p><b>为什么不带 {@code actions} 明细</b>：两个消费者都用不上（{@link #trainingMetrics} 只要容量/CV，
     * plan 节点只要日期/部位/小肌群），而 plan 节点要**整份 history 塞进 prompt**——明细是白付的 token。
     * 明细只在下面算容量/CV 时**内部**读一下。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toDayEntry(String date, String json) {
        try {
            Map<String, Object> log = mapper.readValue(json, Map.class);
            Object actionsObj = log.get("actions");
            List<Map<String, Object>> actions = (List<Map<String, Object>>) actionsObj;
            List<Map<String, Object>> sets = actions == null ? List.of() : actions;
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", date);
            day.put("primaryMuscleGroup", log.get("primaryMuscleGroup"));
            day.put("accessoryMuscleGroups", log.getOrDefault("accessoryMuscleGroups", List.of()));
            day.put("totalVolumeKg", round(totalVolumeKg(sets)));
            day.put("totalCv", dayTotalCv(sets));
            return day;
        } catch (JsonProcessingException e) {
            return null; // 脏数据跳过，不阻断整次查询
        }
    }

    private static long toDateNum(LocalDate date) {
        return Long.parseLong(date.format(DATE_FMT));
    }

    // ═══════════════════ 工具 2：指标计算 ═══════════════════

    /**
     * 纯计算，不依赖存储。
     *
     * <p>输入：{@code actions}（本次 parse 输出，含每组明细）、{@code history}（{@link #trainingLogMemory}
     * 返回的历史，<b>新→旧排列，第 0 条即刚入库的本次</b>）、{@code primaryMuscleGroup}（本次主练部位）。
     *
     * <p><b>环比基准是「上次同部位」，不是「上一条记录」</b>：按 腿→胸→背 轮换，上一条记录必然是另一个
     * 肌群——拿腿日的容量跟胸日比没有意义。取法是<b>跳过第 0 条（本次），往后找第一条同部位</b>；
     * 找不到（首训、或该部位在窗口内只练过这一次）→ 无基准，{@code changePct} 为 null。
     *
     * <p>输出：总容量（吨）、{@code changePct}、{@code totalCv}、每动作指标、
     * {@code volumeDropAlert}、{@code comfortZoneWarning}。
     */
    @ToolMethod(name = "training_metrics",
            description = "计算总容量(吨)、Epley 1RM、真实 CV(组重量变异系数)、环比涨幅(较上次同部位)"
                    + "及硬标记（容量骤降/舒适区警告）")
    public Map<String, Object> trainingMetrics(List<Map<String, Object>> actions,
                                               List<Map<String, Object>> history,
                                               String primaryMuscleGroup) {
        double totalVolumeKg = totalVolumeKg(actions);

        List<Map<String, Object>> actionMetrics = new ArrayList<>();
        for (Map<String, Object> a : actions) {
            actionMetrics.add(actionMetrics(a));
        }

        Map<String, Object> baseline = previousSameGroupSession(history, primaryMuscleGroup);
        Double prevVolumeKg = volumeKgOf(baseline);
        Double changePct = (prevVolumeKg != null && prevVolumeKg > 0)
                ? (totalVolumeKg - prevVolumeKg) / prevVolumeKg * 100
                : null;

        boolean volumeDropAlert = changePct != null && changePct <= VOLUME_DROP_THRESHOLD;
        boolean comfortZoneWarning = changePct != null && comfortZone(baseline, changePct);

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
     * 环比基准：**跳过第 0 条**（刚入库的本次），往后找第一条同部位的记录；找不到返回 null。
     *
     * <p>按位置找而不是按日期算——{@code history} 已经是「本次及之前、日期降序」，第 0 条必是本次，
     * 所以从 1 开始扫就是"本次之前"。
     */
    private static Map<String, Object> previousSameGroupSession(List<Map<String, Object>> history,
                                                                String primaryMuscleGroup) {
        if (history == null || history.size() < 2) {
            return null;
        }
        for (int i = 1; i < history.size(); i++) {
            Map<String, Object> day = history.get(i);
            if (primaryMuscleGroup != null && primaryMuscleGroup.equals(day.get("primaryMuscleGroup"))) {
                return day;
            }
        }
        return null;
    }

    /**
     * 某条历史记录的总容量；记录为空 / 值不是数字 → null。
     */
    private static Double volumeKgOf(Map<String, Object> day) {
        if (day == null) {
            return null;
        }
        Object v = day.get("totalVolumeKg");
        return v instanceof Number n ? n.doubleValue() : null;
    }

    /**
     * 舒适区警告：<b>基准那一条</b>的组间 CV 很低（重量几乎不加，动作做得太"舒服"）且本次容量几乎没动。
     */
    private static boolean comfortZone(Map<String, Object> baseline, double changePct) {
        if (baseline == null) {
            return false;
        }
        Object cv = baseline.get("totalCv");
        if (!(cv instanceof Number n)) {
            return false;
        }
        return n.doubleValue() < COMFORT_ZONE_CV_THRESHOLD && Math.abs(changePct) <= COMFORT_ZONE_RANGE;
    }

    // ═══════════════════ 工具 3：知识检索 ═══════════════════

    /**
     * 检索健身知识库，返回拼好的纯文本；一条没命中就明确说"没有"。
     *
     * <p><b>为什么得是工具</b>：知识检索在图里是 RAG 节点，但节点只在**固定那一刻、按写死的 query** 查一次；
     * 而「这个动作怎么练」「器材被占了换什么」要由模型**按用户当场问的**决定查什么——所以得有工具。
     *
     * <p><b>为什么返回文本而不是 chunks</b>：同 {@code chunksText}——整段喂给模型时，没必要让它再解一层 JSON。
     *
     * <p>query 怎么给：查某个动作用**动作名**（库里每块都带 {@code 动作: xxx} 标签）；要一批候选动作
     * （比如挑替代）用**肌群名**；「该加多少重量」去查「渐进超负荷」。
     *
     * @param query 检索词
     * @param topK  取前几条；不传取 {@value #DEFAULT_TOP_K}
     */
    @ToolMethod(name = "knowledge_search",
            description = "检索健身知识库（动作要领 / 渐进超负荷 / 肌群轮换 / 大带小）。"
                    + "查单个动作怎么做就用动作名，要一批候选动作就用肌群名。"
                    + "知识库里没有的会明确说没有——如实告诉用户，不要自己编。")
    public String knowledgeSearch(String query, Integer topK) {
        List<RagChunk> chunks = ragRetriever.retrieve(query,
                topK != null ? topK : DEFAULT_TOP_K, KNOWLEDGE_COLLECTION);
        if (chunks.isEmpty()) {
            return NO_KNOWLEDGE_HIT;
        }
        return chunks.stream().map(RagChunk::getContent).collect(Collectors.joining("\n\n"));
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
