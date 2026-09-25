package com.agentflow.tool.builtin;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentflow.memory.TestTemplateContext;
import com.agentflow.agent.ChatMessage;
import com.agentflow.agent.ChatResult;
import com.agentflow.agent.LlmGateway;
import com.agentflow.agent.ToolSpec;
import com.agentflow.core.dsl.GraphParser;
import com.agentflow.core.dsl.TemplateResolver;
import com.agentflow.core.exec.LlmNodeExecutor;
import com.agentflow.core.exec.ParallelDispatcher;
import com.agentflow.core.exec.StartNodeExecutor;
import com.agentflow.core.exec.ToolNodeExecutor;
import com.agentflow.core.exec.WorkflowExecutor;
import com.agentflow.core.loop.AgenticLoopExecutor;
import com.agentflow.core.model.NodeDefinition;
import com.agentflow.core.model.WorkflowDefinition;
import com.agentflow.core.routing.ConditionEvaluator;
import com.agentflow.core.state.RunStatus;
import com.agentflow.core.state.WorkflowState;
import com.agentflow.runtime.checkpoint.InMemoryCheckpointStore;
import com.agentflow.tool.ToolDescriptor;
import com.agentflow.tool.ToolRegistry;
import com.agentflow.tool.ToolSchemaValidator;
import com.agentflow.tool.annotation.AgentToolRegistrar;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * T5.4 端到端验收：fitness-coach DSL（去掉 RAG 节点，P6 未落地）跑通全链路，三个内置工具真调用。
 *
 * <p>链路：parse(LLM) → store(TOOL) → history_query(TOOL) ∥ accessory(LLM) → metrics(TOOL) → route(LLM) → report(AGENTIC_LOOP)。
 * 引擎手动装配（等价生产装配，LLM 用脚本桩、TOOL 走真注册中心 + 真 Redis）。
 * 跑两轮验证数据流通：第 1 轮存「背」、第 2 轮练「胸」——轮换通过、history_query 查回两轮、metrics 算出环比与容量骤降标记。
 */
@Testcontainers
class FitnessCoachToolsEndToEndTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private final ObjectMapper mapper = new ObjectMapper();
    private WorkflowExecutor engine;
    private WorkflowDefinition wf;
    private ScriptedLlmGateway gateway;

    @BeforeEach
    void setUp() throws Exception {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.execute((RedisCallback<Object>) connection -> {
            connection.flushDb();
            return null;
        });

        // 注册三个内置工具（走与生产相同的 @AgentTool 扫描 → ToolDescriptor）
        ToolRegistry registry = new ToolRegistry(new ToolSchemaValidator(mapper));
        register(registry, new TrainingLogStoreTool(redis));
        register(registry, new TrainingHistoryQueryTool(redis));
        register(registry, new WorkoutMetricsTool());

        gateway = new ScriptedLlmGateway();
        TemplateResolver resolver = new TemplateResolver(mapper);
        engine = new WorkflowExecutor(
                new InMemoryCheckpointStore(),
                new ParallelDispatcher(4),
                new ConditionEvaluator(),
                List.of(
                        new StartNodeExecutor(),
                        new LlmNodeExecutor(gateway, resolver, TestTemplateContext.withoutMemory(), mapper),
                        new ToolNodeExecutor(registry, resolver, TestTemplateContext.withoutMemory()),
                        new AgenticLoopExecutor(gateway, registry, resolver, TestTemplateContext.withoutMemory(), mapper)), null);

        wf = loadFitnessCoachWithoutRag();
    }

    private void register(ToolRegistry registry, Object toolBean) {
        for (ToolDescriptor d : new AgentToolRegistrar(null, registry, mapper).descriptorsOf(toolBean)) {
            registry.register(d);
        }
    }

    /**
     * 载入 fitness-coach.json，摘掉 RAG 节点 kb_retrieve（P6 落地前可先删，见工作流设计 §7）及它的边与 report 提示引用。
     */
    private WorkflowDefinition loadFitnessCoachWithoutRag() throws Exception {
        String json = new String(getClass().getResourceAsStream(
                "/testdata/workflows/legacy-fitness-coach/fitness-coach.json").readAllBytes(), StandardCharsets.UTF_8);
        WorkflowDefinition wf = new GraphParser(mapper).parse(json);
        wf.getNodes().remove("kb_retrieve");
        wf.getEdges().removeIf(e -> "kb_retrieve".equals(e.getFrom()) || "kb_retrieve".equals(e.getTo()));
        NodeDefinition report = wf.getNodes().get("report");
        report.getConfig().put("systemPrompt", ((String) report.getConfig().get("systemPrompt"))
                .replace("，训练知识={{nodes.kb_retrieve.output.chunks}}。", "。"));
        return wf;
    }

    @Test
    void twoRuns_toolsStoreQueryCompute_acrossChain() {
        // ---------- 第 1 轮：练「背」，首条记录 ----------
        WorkflowState run1 = engine.execute("run-1", wf, Map.of("userLog", "20260812背 哑铃划船 15kg×12×4"));
        assertThat(run1.getStatus()).isEqualTo(RunStatus.SUCCEEDED);

        // store：真工具写入 Redis，首条放行
        @SuppressWarnings("unchecked")
        Map<String, Object> store1 = (Map<String, Object>) run1.getNodeOutputs().get("store").getOutput();
        assertThat(store1.get("stored")).isEqualTo(true);
        assertThat(store1.get("sequenceOk")).isEqualTo(true);
        assertThat(store1.get("lastMuscleGroup")).isNull();

        // history_query：真工具查回刚存的记录（坐姿钢线划船 16/16/16/20 → 容量 712kg、totalCv≈10.19%）
        @SuppressWarnings("unchecked")
        Map<String, Object> hq1 = (Map<String, Object>) run1.getNodeOutputs().get("history_query").getOutput();
        assertThat(hq1.get("recentActions")).asList().hasSize(1);
        Map<?, ?> day1 = (Map<?, ?>) ((List<?>) hq1.get("recentActions")).get(0);
        assertThat(day1.get("muscleGroup")).isEqualTo("背");
        assertThat(((Number) day1.get("totalVolumeKg")).doubleValue()).isEqualTo(712.0);
        assertThat(((Number) day1.get("totalCv")).doubleValue())
                .isCloseTo(10.19, org.assertj.core.data.Offset.offset(0.01));

        // metrics：真工具算出总容量 0.71 吨（显示四舍五入）、CV 10.19%；无历史 → changePct null
        @SuppressWarnings("unchecked")
        Map<String, Object> m1 = (Map<String, Object>) run1.getNodeOutputs().get("metrics").getOutput();
        assertThat(((Number) m1.get("totalVolume")).doubleValue()).isEqualTo(0.71);
        assertThat(m1.get("changePct")).isNull();
        assertThat(m1.get("volumeDropAlert")).isEqualTo(false);
        assertThat(((Number) m1.get("totalCv")).doubleValue())
                .isCloseTo(10.19, org.assertj.core.data.Offset.offset(0.01));

        // report：AGENTIC_LOOP 产出文本
        assertThat(run1.getNodeOutputs().get("report").getOutput()).isNotNull();

        // ---------- 第 2 轮：练「腿」（背→腿 正确轮换），存储已有一轮 → 环比可算 ----------
        WorkflowState run2 = engine.execute("run-2", wf, Map.of("userLog", "20260813腿 杠铃深蹲 20kg×8×3"));
        assertThat(run2.getStatus()).isEqualTo(RunStatus.SUCCEEDED);

        @SuppressWarnings("unchecked")
        Map<String, Object> store2 = (Map<String, Object>) run2.getNodeOutputs().get("store").getOutput();
        assertThat(store2.get("sequenceOk")).isEqualTo(true); // 背 → 腿

        @SuppressWarnings("unchecked")
        Map<String, Object> hq2 = (Map<String, Object>) run2.getNodeOutputs().get("history_query").getOutput();
        assertThat(hq2.get("recentActions")).asList().hasSize(2);

        @SuppressWarnings("unchecked")
        Map<String, Object> m2 = (Map<String, Object>) run2.getNodeOutputs().get("metrics").getOutput();
        // 本次 20*3*8 = 480kg，上次 712kg → -32.58% → volumeDropAlert 触发
        assertThat(((Number) m2.get("totalVolume")).doubleValue()).isEqualTo(0.48);
        assertThat(((Number) m2.get("changePct")).doubleValue())
                .isCloseTo(-32.58, org.assertj.core.data.Offset.offset(0.01));
        assertThat(m2.get("volumeDropAlert")).isEqualTo(true);

        assertThat(run2.getNodeOutputs().get("report").getOutput()).isNotNull();
    }

    /**
     * 脚本化 LLM 桩：按 prompt 内容分发。parse 按 userLog 日期返回对应 JSON（模板绑定已把 userLog 拼进 prompt）。
     */
    static class ScriptedLlmGateway implements LlmGateway {

        @Override
        public String chat(String system, String user) {
            if (user.contains("解析为结构化 JSON")) {
                if (user.contains("20260813")) {
                    return "{\"date\":\"20260813\",\"muscleGroup\":\"腿\","
                            + "\"actions\":[{\"exercise\":\"杠铃深蹲\",\"setsDetail\":["
                            + "{\"weight\":20,\"reps\":8},{\"weight\":20,\"reps\":8},{\"weight\":20,\"reps\":8}]}]}";
                }
                // 坐姿钢线划船：16/16/16/20 → 容量 712kg、CV≈10.19%（证明 CV 穿 Redis 往返）
                return "{\"date\":\"20260812\",\"muscleGroup\":\"背\","
                        + "\"actions\":[{\"exercise\":\"坐姿钢线划船\",\"setsDetail\":["
                        + "{\"weight\":16,\"reps\":15},{\"weight\":16,\"reps\":12},"
                        + "{\"weight\":16,\"reps\":10},{\"weight\":20,\"reps\":6}]}]}";
            }
            if (user.contains("按大带小原则")) {
                return "{\"accessoryGroups\":[\"二头\"]}";
            }
            if (user.contains("只输出 route 和 reason")) {
                return "{\"route\":\"NORMAL\",\"reason\":\"训练稳定\"}";
            }
            throw new AssertionError("意外 chat 调用：" + user);
        }

        @Override
        public ChatResult chatWithTools(String system, List<ChatMessage> history, List<ToolSpec> tools) {
            return new ChatResult("复盘报告：容量与强度稳定。处方：按 腿→胸→背 轮换，下次练腿。", List.of());
        }
    }
}
