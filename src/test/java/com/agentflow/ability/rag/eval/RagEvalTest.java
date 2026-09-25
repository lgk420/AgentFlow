package com.agentflow.ability.rag.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.agentflow.api.IngestDocument;
import com.agentflow.api.IngestRequest;
import com.agentflow.api.RagApi;
import com.agentflow.ability.rag.Reranker;
import com.agentflow.ability.rag.RetrievedChunk;
import com.agentflow.ability.rag.Retriever;
import com.agentflow.ability.rag.eval.RetrievalMetrics.GroupSummary;
import com.agentflow.ability.rag.eval.RetrievalMetrics.Outcome;
import com.agentflow.ability.rag.eval.RetrievalMetrics.Summary;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.opentest4j.TestAbortedException;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 检索质量评测台（T6.6 基线，T6.7 加重排对照，T6.8 加阈值扫描）——「评测」基础设施，不是常规单测。
 *
 * <p><b>只测检索层</b>：直接调 {@link Retriever}（与 {@link Reranker}），不走工作流、不调 LLM。
 * 这样一次全量 50 条几秒跑完，可以反复跑多组配置做对照；且分数掉了能确定是检索的锅，
 * 不会是编排或生成的锅。生成质量评测（答案准不准、有没有编）需要 LLM-as-Judge，属另一块，本类不做。
 *
 * <p><b>对照设计（T6.7）</b>：两边<b>召回条数相同</b>，只变排序方式——
 * <ul>
 *   <li>基线：向量召回 {@code TOP_K} 条，取前 {@code TOP_K} 条</li>
 *   <li>重排：向量召回 {@code TOP_K} 条（同一批），重排后取前 {@code TOP_K} 条</li>
 * </ul>
 * <b>候选池 = 输出条数</b>，不存在"多给候选所以更容易"的争议，差值纯粹来自排序。
 * 召回 <b>3</b> 条是有意的：@3 基线已达 0.976，意味着"找得到"不是问题，
 * 那么重排的<b>理论天花板就是 @3</b>——@1 能逼近 @3 就说明重排把对的顶到了第一。
 *
 * <p><b>运行前提</b>：compose 环境在线（postgres + ollama + 重排服务）。
 * 前两者不可用时整类跳过（{@link TestAbortedException}）；重排服务不可用时只跑基线行并明确标注
 * ——不静默降级，避免"重排挂了"被误读成"重排没用"。
 * <pre>
 *   bash scripts/dev.sh up --llm --rerank
 *   mvn test -Dtest=RagEvalTest
 * </pre>
 *
 * <p><b>语料每次重建</b>：跑之前先清空 {@code eval} collection 再灌，保证「评测的语料」与
 * {@code src/test/resources/eval/kb/*.md} 一致。清理用与检索同一套 metadata 过滤器，
 * <b>只命中本评测的 collection，不会碰到其它知识库</b>。
 * 改了 md 要重新生成语料：{@code node scripts/build_kb_json.js src/test/resources/eval/kb src/test/resources/eval/corpus.json}
 *
 * <p><b>产出</b>：{@code reports/rag-eval/} 下的 markdown 报告——面试时可以直接打开的那张对照表。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagEvalTest {

    /** 评测专用 collection，与线上/演示用的 workout_kb 物理隔离（靠 metadata.collection 区分）。 */
    private static final String COLLECTION = "workout_kb_eval";

    private static final String GOLDEN_SET = "eval/rag-golden-set.json";

    private static final String CORPUS = "eval/corpus.json";

    /**
     * 召回条数 = 重排后返回条数 = 3（两边相同，池子即输出）。
     *
     * <p>不设成 10、20：那样等于把大半个语料递过去，"重排挑对的"变得过于容易，收益虚高。
     * 与真实场景的比例（top3/十万 ≈ 0.003%）相比，3/42 = 7% 仍然偏高——所以
     * <b>这份报告只用来看趋势，不能当绝对水平报</b>。
     */
    private static final int TOP_K = 3;

    /**
     * HitRate 的分档。分档是嵌套的（@1 命中必然 @2 @3 也命中），单调递增，差值说明「排得够不够前」。
     *
     * <p><b>档位按信息量选，不是按习惯</b>：实测 @3 与 @5 完全相同（语料只有 42 块，
     * 凡进得了前 5 的都早在第 3 名内），@5 提供零信息量故去掉；而 @2 既不等于 @1 也不等于 @3，
     * 有区分度故保留。@1 是重排的主靶子——把对的从第 2、3 名顶到第 1 名。
     */
    private static final int[] CUTOFFS = {1, 2, 3};

    /**
     * 阈值扫描的取值。跨度覆盖两条路径的量级——向量分正负都挤在 0.5~0.8，
     * 重排分正样本 0.78 而负样本 0.007，所以低阈值档对重排才有意义。
     */
    private static final double[] THRESHOLD_SWEEP = {0.0, 0.05, 0.1, 0.2, 0.3, 0.5, 0.6, 0.7};

    /** 一组待测配置：名字 + 是否启用重排。其余条件两边完全一致。 */
    private record EvalConfig(String name, boolean rerank) {
    }

    /** 一组配置的完整结果。 */
    private record ConfigResult(EvalConfig config, List<Outcome> outcomes, Summary summary, List<GroupSummary> groups) {
    }

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RagApi ragApi;

    @Autowired
    private Retriever retriever;

    @Autowired
    private Reranker reranker;

    /** 本次灌入的语料块数，供报告头部展示。 */
    private int ingestedCount;

    /** 重排服务是否可用——不可用时只跑基线行，并在报告里标注。 */
    private boolean rerankAvailable;

    @BeforeAll
    void prepareCorpus() {
        try {
            resetCollection();
            ingestedCount = ingestCorpus();
            System.out.printf("评测语料已就绪：collection=%s, %d chunks%n", COLLECTION, ingestedCount);
        } catch (Exception e) {
            throw new TestAbortedException("评测环境不可用（需 compose 的 postgres + ollama）: " + e.getMessage(), e);
        }
        rerankAvailable = probeRerank();
        if (!rerankAvailable) {
            System.out.println("⚠️ 重排服务不可达，本次只跑基线行"
                    + "（起服务：bash scripts/dev.sh up --llm --rerank）");
        }
    }

    /**
     * 清空评测 collection。
     * 过滤器与检索同源（按 metadata.collection 匹配），物理上只能删到 COLLECTION 自己，
     * 不会影响其它知识库——这也是评测能反复跑而不用担心污染数据的前提。
     */
    private void resetCollection() {
        vectorStore.delete(new FilterExpressionBuilder().eq("collection", COLLECTION).build());
    }

    private int ingestCorpus() throws IOException {
        List<IngestDocument> documents = GoldenSet.Corpus.load(CORPUS).documents().stream()
                .map(d -> new IngestDocument(d.text(), d.metadata()))
                .toList();
        IngestRequest request = new IngestRequest();
        request.setDocuments(documents);
        return (int) ragApi.ingest(COLLECTION, request).get("ingested");
    }

    /**
     * 重排服务探活。不可用就只跑基线行——<b>不静默降级</b>：
     * 若照跑而让异常被吞掉，报告会显示"重排没有提升"，那是假的。
     *
     * <p><b>重试一次的理由是冷启动</b>：模型长期未用会被 Xinference 卸载，首次调用要先把它
     * 加载回内存（CPU 上轻易超过 30s）。第一次即便超时，加载也已在进行；第二次通常就是热调用。
     * 不重试的话，一次冷启动就会被误报成"服务不可达"，整份报告只剩基线行。
     */
    private boolean probeRerank() {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                reranker.rerank("探活", List.of(new RetrievedChunk("探活文本", 0.0)), 1);
                return true;
            } catch (RuntimeException e) {
                System.out.printf("重排探活第 %d/2 次失败：%s%n", attempt, e.getMessage());
            }
        }
        return false;
    }

    @Test
    void evaluateRetrievalQuality() throws Exception {
        GoldenSet goldenSet = GoldenSet.load(GOLDEN_SET);

        List<EvalConfig> configs = rerankAvailable
                ? List.of(new EvalConfig("纯向量（基线）", false), new EvalConfig("+ 重排", true))
                : List.of(new EvalConfig("纯向量（基线）", false));

        List<ConfigResult> results = new ArrayList<>();
        for (EvalConfig config : configs) {
            List<Outcome> outcomes = runConfig(goldenSet, config);
            results.add(new ConfigResult(config, outcomes,
                    RetrievalMetrics.summarize(outcomes, CUTOFFS),
                    RetrievalMetrics.byType(outcomes, CUTOFFS)));
        }

        Path report = writeReport(goldenSet, results);
        printToConsole(goldenSet, results, report);

        // 冒烟下限：真出问题了（语料没灌上、嵌入挂掉）分数会塌到接近 0，这里要能拦住。
        // 不是质量目标——质量靠报告里那张对照表看趋势。
        Summary baseline = results.get(0).summary();
        assertThat(baseline.hitRateAt(deepestCutoff()))
                .as("基线 HitRate@%d 塌到 %.2f，检索链路可能坏了（语料/嵌入/过滤）",
                        deepestCutoff(), baseline.hitRateAt(deepestCutoff()))
                .isGreaterThan(0.5);
        assertThat(baseline.positives() + baseline.negatives()).isEqualTo(goldenSet.cases().size());
    }

    /** 跑一组配置：召回 TOP_K 条，按配置决定是否重排，都取前 TOP_K。 */
    private List<Outcome> runConfig(GoldenSet goldenSet, EvalConfig config) {
        List<Outcome> outcomes = new ArrayList<>();
        for (GoldenSet.Case testCase : goldenSet.cases()) {
            long start = System.nanoTime();
            List<RetrievedChunk> chunks = retriever.retrieve(testCase.question(), TOP_K, COLLECTION);
            if (config.rerank()) {
                // 不走 RagNodeExecutor：评测要测检索质量而非 DAG 编排，
                // 且绕过执行器的降级——重排挂了必须让测试失败，不能被静默记成"没提升"。
                chunks = reranker.rerank(testCase.question(), chunks, TOP_K);
            }
            long millis = (System.nanoTime() - start) / 1_000_000;
            outcomes.add(new Outcome(testCase,
                    chunks.stream().map(RagEvalTest::labelOf).toList(),
                    chunks.stream().map(RetrievedChunk::getScore).toList(),
                    millis));
        }
        return outcomes;
    }

    /** chunk 的稳定标识：{@code 文件名#动作名}，与评测集 expect 同口径。 */
    private static String labelOf(RetrievedChunk chunk) {
        Map<String, Object> metadata = chunk.getMetadata();
        return metadata.get("source") + "#" + metadata.get("label");
    }

    private void printToConsole(GoldenSet goldenSet, List<ConfigResult> results, Path report) {
        System.out.println();
        System.out.printf("=== RAG 检索评测：%s（%d 条）%n", goldenSet.name(), goldenSet.cases().size());
        System.out.println("              " + cutoffHeader() + " | Recall | MRR | 正均分 | 负均分 | 均分差 | P50 | P99");
        results.forEach(r -> System.out.printf("%-13s %s%n", r.config().name(), metricCells(r.summary())));
        System.out.println("报告：" + report.toAbsolutePath());
    }

    private Path writeReport(GoldenSet goldenSet, List<ConfigResult> results) throws IOException {
        Path dir = Path.of("reports", "rag-eval");
        Files.createDirectories(dir);
        String markdown = renderReport(goldenSet, results);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dated = dir.resolve("rag-eval-" + stamp + ".md");
        Files.writeString(dated, markdown);
        Files.writeString(dir.resolve("latest.md"), markdown);
        return dated;
    }

    private String renderReport(GoldenSet goldenSet, List<ConfigResult> results) {
        Summary baseline = results.get(0).summary();
        StringBuilder sb = new StringBuilder();
        sb.append("# RAG 检索评测报告\n\n");
        sb.append("- 知识库：`").append(COLLECTION).append("`（").append(ingestedCount).append(" chunks）\n");
        sb.append("- 评测集：`").append(goldenSet.name()).append("`（").append(goldenSet.cases().size()).append(" 条，")
                .append(baseline.positives()).append(" 正 / ").append(baseline.negatives()).append(" 负）\n");
        sb.append("- 召回/返回：各 ").append(TOP_K).append(" 条（两边**候选池相同**，差值纯粹来自排序）\n");
        sb.append("- 链路：bge-m3（Ollama 本地嵌入）+ pgvector + bge-reranker-v2-m3（Xinference）\n");
        sb.append("- 生成时间：").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");

        appendOverview(sb, results);
        appendThresholdSweep(sb, results);
        results.forEach(r -> appendGroups(sb, r));
        results.forEach(r -> appendMisses(sb, r));
        return sb.toString();
    }

    /**
     * 阈值扫描——回答「拒答的阈值该设多少」。
     *
     * <p>因为结果按分数降序，只要 <b>top1 低于阈值</b> 就等于「一条都不剩」= {@code hit=false}，
     * 所以只需看每条用例的 top1 分数就能算出这套取舍。
     *
     * <p><b>挑阈值的标准</b>：在正样本误杀率尽量低的前提下，负样本拦截率尽量高。
     * 两条路径各扫一遍——它们的分数不是一个量纲，最优阈值也必然不同。
     */
    private void appendThresholdSweep(StringBuilder sb, List<ConfigResult> results) {
        sb.append("\n## 阈值扫描（拒答阈值该设多少）\n\n");
        sb.append("> 阈值过滤的是**最终分数**（重排后是相关性分，未重排是向量余弦分）。\n");
        sb.append("> 结果按分数降序，故 `top1 < 阈值` 等价于「一条都不剩」= `hit=false` = 走拒答分支。\n");
        sb.append(">\n");
        sb.append("> 挑法：**正样本·误拒尽量低** 的前提下，**负样本·拒答尽量高**。\n");
        sb.append(">\n");
        sb.append("> ### 口径声明（别和上面「各 3 条」混淆）\n");
        sb.append(">\n");
        sb.append("> 本表有两处「top1」，含义不同，都要明确：\n");
        sb.append(">\n");
        sb.append("> | 概念 | 用途 | 判据 |\n");
        sb.append("> |---|---|---|\n");
        sb.append("> | **top1 的分数** | 阈值**比较的对象** | `top1Score >= 阈值` |\n");
        sb.append("> | **第 1 名是否命中** | 判定**答对 / 答错** | `hitAt(1)`，即 HitRate@1 口径 |\n");
        sb.append(">\n");
        sb.append("> **本表用 @1 判对错，不是 @").append(deepestCutoff()).append("**（列名里的「(@1)」即此意）。理由：\n");
        sb.append("> ① 阈值决策本身就基于 top1 的分数——结果降序，`top1 < 阈值` ⟺ 全被拒；\n");
        sb.append("> ② 第 1 名对不对直接决定 LLM 拿到的主资料对不对。\n");
        sb.append(">\n");
        sb.append("> 用 @").append(deepestCutoff())
                .append(" 判会把「塞进去了就算答对」算进来，偏宽松，且与总览的 HitRate@1 对不上号。\n");
        sb.append(">\n");
        sb.append("> 自查：**阈值 0.00 那一行的「答对(@1)」应正好等于总览的 HitRate@1**（0.810 / 0.905）。\n");
        sb.append(">\n");
        sb.append("> | 列 | 含义 | 评价 |\n");
        sb.append("> |---|---|---|\n");
        sb.append("> | 正样本·答对 | 未被拒 且 **第 1 名对** | ✅ 理想 |\n");
        sb.append("> | 正样本·答错 | 未被拒 但 **第 1 名错** | ❌ 会拿错资料作答（幻觉）|\n");
        sb.append("> | 正样本·误拒 | **被拒** 且 第 1 名对 | ⚠️ **真损失**——本可答对却说不知道 |\n");
        sb.append("> | 正样本·拒对 | 被拒 且 第 1 名错 | ✅ 拒答是对的 |\n");
        sb.append("> | 负样本·拒答 | 被拒 | ✅ 理想 |\n");
        sb.append("> | 负样本·硬答 | **没**被拒 | ❌ 会拿无关资料作答（幻觉）|\n");
        sb.append(">\n");
        sb.append("> 前四列之和恒为 1；**阈值 0.00 那一行的「答对率」应正好等于总览的 HitRate@1** —— 可据此自查口径。\n");

        for (ConfigResult result : results) {
            List<Outcome> positives = result.outcomes().stream()
                    .filter(o -> !o.testCase().isNegative()).toList();
            List<Outcome> negatives = result.outcomes().stream()
                    .filter(o -> o.testCase().isNegative()).toList();
            sb.append("\n### ").append(result.config().name()).append("\n\n");
            // 列名带「(@1)」标明对错判据，否则读者会误用上面「各 3 条」的口径
            sb.append("| 阈值 | 正样本·答对(@1) | 正样本·答错(@1) | 正样本·误拒(@1) | 正样本·拒对(@1)")
                    .append(" | 负样本·拒答 | 负样本·硬答 |\n");
            sb.append("|---|---|---|---|---|---|---|\n");
            for (double threshold : THRESHOLD_SWEEP) {
                // 正样本按「第 1 名是不是正确的」× 「有没有被阈值拒绝」拆成完备四类。
                //
                // 判据统一用 @1（不是 @3）：阈值决策本身就基于 top1 的分数（降序排列下
                // top1 < 阈值 ⟺ 全被拒），而第 1 名对不对直接决定 LLM 拿到的主资料对不对。
                // 用 @3 会把"塞进去了就算答对"算进来，偏宽松，且与 HitRate@1 对不上号。
                double answeredAndHit = ratio(positives,
                        o -> o.top1Score() >= threshold && o.hitAt(1));
                double answeredButMiss = ratio(positives,
                        o -> o.top1Score() >= threshold && !o.hitAt(1));
                double refusedButHit = ratio(positives,
                        o -> o.top1Score() < threshold && o.hitAt(1));
                double refusedAndMiss = ratio(positives,
                        o -> o.top1Score() < threshold && !o.hitAt(1));
                double negativeRefused = ratio(negatives, o -> o.top1Score() < threshold);
                sb.append(String.format("| %.2f | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f |%n",
                        threshold, answeredAndHit, answeredButMiss, refusedButHit, refusedAndMiss,
                        negativeRefused, 1 - negativeRefused));
                // 四类构成完备划分，且阈值 0 行的"答对率"应正好等于 HitRate@1
                if (Math.abs(answeredAndHit + answeredButMiss + refusedButHit + refusedAndMiss - 1.0) > 1e-9) {
                    throw new IllegalStateException("阈值扫描的分类不构成完备划分，请检查口径");
                }
            }
        }
    }

    private static double ratio(List<Outcome> outcomes, java.util.function.Predicate<Outcome> predicate) {
        return outcomes.isEmpty() ? 0.0 : (double) outcomes.stream().filter(predicate).count() / outcomes.size();
    }

    private void appendOverview(StringBuilder sb, List<ConfigResult> results) {
        sb.append("\n## 总览\n\n");
        sb.append("| 配置 | ").append(cutoffHeader())
                .append(" | Recall@").append(TOP_K).append(" | MRR | 正样本均分 | 负样本均分 | 均分差 | P50 | P99 |\n");
        sb.append("|").append("---|".repeat(1 + CUTOFFS.length + 7)).append("\n");
        for (ConfigResult r : results) {
            sb.append("| ").append(r.config().name()).append(" | ").append(metricCells(r.summary())).append(" |\n");
        }

        if (results.size() > 1) {
            Summary base = results.get(0).summary();
            Summary reranked = results.get(1).summary();
            sb.append("\n**重排收益**（相对基线）：");
            for (int cutoff : CUTOFFS) {
                double delta = reranked.hitRateAt(cutoff) - base.hitRateAt(cutoff);
                sb.append(String.format(" @%d %+.3f", cutoff, delta));
            }
            sb.append(String.format("，MRR %+.3f，P50 %+dms%n",
                    reranked.mrr() - base.mrr(), reranked.p50Millis() - base.p50Millis()));
        } else {
            sb.append("\n> ⚠️ **本次只有基线行**——重排服务不可达。"
                    + "起服务后重跑：`bash scripts/dev.sh up --llm --rerank`\n");
        }

        sb.append("\n> **档位怎么选的**：HitRate 的分档是嵌套的（@1 命中必然 @2 @3 也命中），单调递增，\n");
        sb.append("> 差值说明「排得够不够前」。档位按**信息量**选，不是按习惯：\n");
        sb.append(">\n");
        sb.append("> - 实测 **@3 与 @5 完全相同**（凡进得了前 5 的都早在第 3 名内）——@5 零信息量，去掉；\n");
        sb.append("> - **@2 既不等于 @1 也不等于 @3**，有区分度，保留；\n");
        sb.append("> - @1 是重排的主靶子（把对的从第 2、3 名顶到第 1 名）。\n");
        sb.append(">\n");
        sb.append("> **@3 是重排的理论天花板**：@3 已经很高，说明「找得到」不是问题，\n");
        sb.append("> 那么重排最多只能把 @1 顶到 @3 的水平。**@1 逼近 @3 = 重排几乎完美**。\n");
        sb.append(">\n");
        sb.append("> ⚠️ **语料规模决定这份报告只能用来看趋势**：只有 42 个块，取 top3 = 全库的 7%\n");
        sb.append("> （真实场景约 0.003%）。候选池小 → 重排「挑对的」更容易 → **绝对值虚高**。\n");
        sb.append("> 要报绝对水平，得先把干扰语料扩到 200~500 块。\n");
        sb.append(">\n");
        sb.append("> **均分差**（正样本 top1 均分 − 负样本 top1 均分）是「相似度阈值能不能生效」的判据：\n");
        sb.append("> 差得开 → 阈值可以分开两者；混在一起 → 阈值救不了，得先改检索。\n");
        sb.append(">\n");
        sb.append("> Recall 当前恒等于 HitRate@").append(deepestCutoff())
                .append("：评测集每条用例只标了一个期望块，命中率即召回率。")
                .append("待出现「一个问题的答案跨多块」的用例后两者才会分开。\n");
    }

    private void appendGroups(StringBuilder sb, ConfigResult result) {
        sb.append("\n## 分类型 · ").append(result.config().name()).append("\n\n");
        sb.append("| 类型 | 条数 | ").append(cutoffHeader()).append(" | MRR | 说明 |\n");
        // 列数 = 类型 + 条数 + 各档 HitRate + MRR + 说明 = 4 + CUTOFFS.length，
        // 与上一行的表头严格相等——对不上 Markdown 就不会渲染成表格（踩过）。
        sb.append("|").append("---|".repeat(4 + CUTOFFS.length)).append("\n");
        for (GroupSummary group : result.groups()) {
            Summary g = group.summary();
            sb.append("| ").append(group.type()).append(" | ").append(g.positives() + g.negatives()).append(" | ");
            for (int cutoff : CUTOFFS) {
                sb.append(g.positives() == 0 ? "—" : String.format("%.3f", g.hitRateAt(cutoff))).append(" | ");
            }
            sb.append(g.positives() == 0 ? "—" : String.format("%.3f", g.mrr()))
                    .append(" | ").append(TYPE_NOTES.getOrDefault(group.type(), "")).append(" |\n");
        }
    }

    private static final Map<String, String> TYPE_NOTES = Map.of(
            "直问", "直接用动作名问，最简单，应接近满分",
            "口语化", "真实用户的问法，检索最容易在这里翻车",
            "跨块", "问题与知识库措辞差异大，考验语义检索的边界",
            "负样本", "知识库无此内容，看负样本 top1 分数与正样本差多少");

    /** 最深的一档——用于"未命中"判定与冒烟下限断言。 */
    private static int deepestCutoff() {
        return CUTOFFS[CUTOFFS.length - 1];
    }

    /** HitRate 各档表头，如 {@code "HitRate@1 | HitRate@2 | HitRate@3"}。 */
    private static String cutoffHeader() {
        return Arrays.stream(CUTOFFS).mapToObj(c -> "HitRate@" + c).collect(Collectors.joining(" | "));
    }

    /** 一行指标值（不含名称列）：各档 HitRate + Recall + MRR + 正负均分 + 分差 + 延迟。 */
    private static String metricCells(Summary s) {
        StringBuilder cells = new StringBuilder();
        for (int cutoff : CUTOFFS) {
            cells.append(String.format("%.3f", s.hitRateAt(cutoff))).append(" | ");
        }
        return cells.append(String.format("%.3f", s.recall()))
                .append(" | ").append(String.format("%.3f", s.mrr()))
                .append(" | ").append(String.format("%.3f", s.positiveTop1()))
                .append(" | ").append(String.format("%.3f", s.negativeTop1()))
                .append(" | ").append(String.format("%+.3f", s.scoreGap()))
                .append(" | ").append(s.p50Millis()).append("ms")
                .append(" | ").append(s.p99Millis()).append("ms")
                .toString();
    }

    /** 列出没命中的用例——报告里最该看的部分，优化就从这里开始。 */
    private void appendMisses(StringBuilder sb, ConfigResult result) {
        List<Outcome> misses = result.outcomes().stream()
                .filter(o -> !o.testCase().isNegative() && !o.hitAt(deepestCutoff()))
                .toList();

        sb.append("\n## 未命中用例 · ").append(result.config().name())
                .append("（前 ").append(deepestCutoff()).append(" 条内未找到，")
                .append(misses.size()).append(" 条）\n\n");
        if (misses.isEmpty()) {
            sb.append("全部命中。\n");
            return;
        }
        sb.append("| id | 类型 | 问题 | 期望 | 实际召回（分数） |\n|---|---|---|---|---|\n");
        for (Outcome o : misses) {
            sb.append(String.format("| %s | %s | %s | %s | %s |%n",
                    o.testCase().id(), o.testCase().type(), o.testCase().question(),
                    String.join("、", o.testCase().expect()),
                    renderRetrieved(o)));
        }
    }

    private static String renderRetrieved(Outcome o) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < o.retrieved().size(); i++) {
            if (i > 0) {
                sb.append("<br>");
            }
            sb.append(o.retrieved().get(i)).append("（").append(String.format("%.2f", o.scores().get(i))).append("）");
        }
        return sb.toString();
    }
}
