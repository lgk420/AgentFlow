package com.agentflow.ability.rag.eval;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索质量指标（T6.6）——纯计算，不碰 IO，可单独断言。
 *
 * <p>指标口径：
 * <ul>
 *   <li><b>HitRate@K</b>：正样本中，「期望块至少有一个进了前 K 条」的比例。<b>同时算多档 K</b>——
 *       因为分档是嵌套的（@1 命中必然 @3 @5 也命中），三档的差值说明「排得够不够前」。
 *       @5 在小语料上会饱和（42 个块取 top5 等于返回了整个库的 12%），@1 才是区分度所在，
 *       也正是重排发力的地方。</li>
 *   <li><b>Recall@K</b>：平均「期望块被召回的比例」。一个问题的答案跨多块时，HitRate 会放水
 *       （命中一块就算过），Recall 才看得出漏了多少。</li>
 *   <li><b>MRR</b>：平均「第一个命中排名的倒数」。衡量排序质量——命中但排在末尾，对 LLM 上下文同样不友好。</li>
 *   <li><b>负样本均分</b>：负样本 top1 相似度均值。单独看没意义，要和<b>正样本均分</b>对比：
 *       两组分数拉得开，才说明「相似度阈值」能生效；混在一起则是阈值救不了的，得先改检索。</li>
 * </ul>
 *
 * <p>注意：<b>HitRate 的 cutoff 不能任意小</b>。K 是生产参数（喂给 LLM 几条），由上下文预算决定，
 * 改 K 来让分数好看是自欺欺人；要提升评测区分度只能靠扩语料（同领域干扰项），不能靠调 K。
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /** 单条用例的检索结果。 */
    public record Outcome(GoldenSet.Case testCase, List<String> retrieved, List<Double> scores, long millis) {

        /** 第一个命中在召回列表中的位置（1 起）；未命中为 0。 */
        public int firstHitRank() {
            if (testCase.isNegative()) {
                return 0;
            }
            for (int i = 0; i < retrieved.size(); i++) {
                if (testCase.expect().contains(retrieved.get(i))) {
                    return i + 1;
                }
            }
            return 0;
        }

        /** 命中是否落在前 cutoff 条内。 */
        public boolean hitAt(int cutoff) {
            int rank = firstHitRank();
            return rank > 0 && rank <= cutoff;
        }

        /** 期望块被召回的比例；用于看清"命中了一块但漏了另一块"。 */
        public double recall() {
            if (testCase.isNegative()) {
                return 0.0;
            }
            long found = testCase.expect().stream().filter(retrieved::contains).count();
            return (double) found / testCase.expect().size();
        }

        public double reciprocalRank() {
            int rank = firstHitRank();
            return rank == 0 ? 0.0 : 1.0 / rank;
        }

        public double top1Score() {
            return scores.isEmpty() ? 0.0 : scores.get(0);
        }
    }

    /** 一组用例的汇总。 */
    public record Summary(int positives, int negatives, Map<Integer, Double> hitRates, double recall, double mrr,
                          double positiveTop1, double negativeTop1, long p50Millis, long p99Millis) {

        public double hitRateAt(int cutoff) {
            return hitRates.getOrDefault(cutoff, 0.0);
        }

        /** 正负样本均分的差距——阈值能否生效的判据。 */
        public double scoreGap() {
            return positiveTop1 - negativeTop1;
        }
    }

    /** 汇总；正负样本分开算。cutoffs 是 HitRate 的分档（如 @1 / @3 / @5），须 ≤ 实际检索条数。 */
    public static Summary summarize(List<Outcome> outcomes, int[] cutoffs) {
        List<Outcome> positives = outcomes.stream().filter(o -> !o.testCase().isNegative()).toList();
        List<Outcome> negatives = outcomes.stream().filter(o -> o.testCase().isNegative()).toList();

        Map<Integer, Double> hitRates = new LinkedHashMap<>();
        for (int cutoff : cutoffs) {
            hitRates.put(cutoff, average(positives.stream().map(o -> o.hitAt(cutoff) ? 1.0 : 0.0).toList()));
        }
        double recall = average(positives.stream().map(Outcome::recall).toList());
        double mrr = average(positives.stream().map(Outcome::reciprocalRank).toList());
        double positiveTop1 = average(positives.stream().map(Outcome::top1Score).toList());
        double negativeTop1 = average(negatives.stream().map(Outcome::top1Score).toList());

        List<Long> millis = outcomes.stream().map(Outcome::millis).sorted(Comparator.naturalOrder()).toList();

        return new Summary(positives.size(), negatives.size(), hitRates, recall, mrr,
                positiveTop1, negativeTop1, percentile(millis, 0.50), percentile(millis, 0.99));
    }

    /** 按用例类型分组汇总（直问 / 口语化 / 跨块 / 负样本），用于定位是哪类问法在拖分。 */
    public static List<GroupSummary> byType(List<Outcome> outcomes, int[] cutoffs) {
        return outcomes.stream()
                .map(o -> o.testCase().type())
                .distinct()
                .sorted()
                .map(type -> {
                    List<Outcome> group = outcomes.stream().filter(o -> o.testCase().type().equals(type)).toList();
                    return new GroupSummary(type, summarize(group, cutoffs));
                })
                .toList();
    }

    /** 一个类型的汇总。 */
    public record GroupSummary(String type, Summary summary) {
    }

    private static double average(List<Double> values) {
        return values.isEmpty() ? 0.0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /** 最近秩百分位（样本少时取偏保守的下界）。 */
    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
