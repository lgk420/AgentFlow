# AgentFlow

> ## 📌 本分支：`release-v1-fitness-coach`
>
> **「健身教练助手 v1」的发布快照** —— 基于 `f52302a`（2026-09-17），**P0–P7 全部完成**。
>
> **包含什么**：DAG 编排引擎、手写 Agent 循环、工具注册中心 + MCP、RAG 检索节点、
> 事件驱动执行（共享 checkpoint / 幂等 / 死信）——即下表「当前进度」中 ✅ 的全部内容。
> **这是一个能端到端跑通、功能自洽的版本**，工作流从训练日志解析一路跑到复盘报告与下次处方。
>
> **不包含**（在 `main` 上继续做的 RAG 增强）：
>
> | 编号 | 内容 | 效果 |
> |---|---|---|
> | T6.5 | 灌库元数据（chunk 带 `source`/`label`，供引用溯源） | 检索结果能自报来源 |
> | T6.6 | 检索评测台（50 条评测集 + HitRate / MRR + 阈值扫描） | 有了量化基线 |
> | T6.7 | cross-encoder 重排（Xinference + bge-reranker-v2-m3） | HitRate@1 `0.810 → 0.905` |
> | T6.8 | 相似度阈值与拒答（DSL 条件边分流） | 负样本硬答 `100% → 0%` |
>
> **为什么单独拉这条分支**：v1 是一个**功能完整的参照点**——P0–P7 的能力自成一体；
> 后面的两阶段检索是在它之上的增强。分开是为了让「v1 是什么」有一个固定的答案，
> 也方便对着这个版本讲「当时是怎么做的、后来改了什么」。
>
> **跑起来**：见下方[「快速开始」](#快速开始)。

轻量级 **Agent 工作流编排引擎**：用 JSON DSL 定义带 LLM / Agent / 工具 / RAG 节点的 DAG 工作流，就绪度调度执行。健身教练助手是端到端示例——从训练日志解析到生成复盘报告与下一次训练处方，全部经工作流自动完成。

## 核心特性

- **DAG 编排引擎**：JSON DSL → 图解析 → 拓扑校验（判环、聚合报错）→ 就绪度调度（wave 批派发、并发收敛、死分支传播、停滞检测）
- **手写 Agent 循环**：AGENTIC_LOOP 节点 = 手写 LLM↔Tool 多轮循环（解析 tool_calls → 调工具 → 消息回放 → 迭代兜底），不依赖框架自带的 agent 实现
- **工具注册中心**：一套 JSON Schema 两用（喂 LLM 的函数定义 + 入参校验），支持 `@AgentTool` 注解扫描、动态注册 API、MCP 协议对齐
- **RAG 作为图内节点**：pgvector + 本地嵌入模型（bge-m3）+ collection 多库隔离 + 灌库 API，与整图调度解耦
- **三层测试策略**：打桩单测 / Testcontainers 集成 / 真实模型（Ollama）端到端
- **事件驱动改造**：EventBus + Redis Streams，at-least-once 投递 + 幂等消费 + 死信（可平迁 Kafka）

## 架构总览

```
JSON DSL（工作流定义）
        │
        ▼
GraphParser ──▶ GraphValidator（DAG 校验 / 判环 / 聚合报错）──▶ WorkflowStore（Redis 版本化）
                                                                    │
                                                                    ▼
                                                        WorkflowExecutor（就绪度调度）
                                                                    │
                                    ┌───────────────┬───────────────┼───────────────┐
                              LlmNodeExecutor  ToolNodeExecutor  RagNodeExecutor  AgenticLoopExecutor
                                    │               │               │               │
                                    ▼               ▼               ▼               ▼
                              LLM 网关        工具注册中心       向量检索 RAG      LLM↔工具循环
                              (DeepSeek/       (@AgentTool/       (pgvector+      (手写 tool-use
                               Ollama 可选)     API注册/MCP)       bge-m3)          loop)
```

## 技术栈

| 类别 | 选型 |
|---|---|
| 语言/框架 | Java 21 · Spring Boot 3.5 · Spring AI 1.0 |
| 存储 | Redis 7（状态 / 事件流）· pgvector/pg16（向量） |
| 模型 | OpenAI 兼容（DeepSeek 默认，可换 Ollama）· 本地嵌入 bge-m3（Ollama） |
| 工具协议 | MCP（官方 Java SDK）· JSON Schema 校验 |
| 测试 | Testcontainers · JUnit 5 · Mockito |

## 快速开始

前置：Docker（WSL 或 Linux）、Java 21、Maven。Docker 命令在 Ubuntu/WSL 终端执行，Java 构建运行在 Windows 侧（WSL2 localhost 转发连通）。

```bash
# [WSL] 1. 起依赖（redis + postgres + ollama）——docker 命令在 Ubuntu/WSL 终端执行
bash scripts/dev.sh up --llm

# [WSL] 2. 拉本地嵌入模型（RAG 用）
docker compose --profile local-llm exec ollama ollama pull bge-m3

# [Windows] 3. 配置 LLM key（在启动应用的 Windows 终端里设）
export LLM_API_KEY=<你的 key>

# [Windows] 4. 起应用（Windows 侧，经 WSL2 转发连 localhost:6379/5432）
mvn spring-boot:run        # localhost:8080
```

### 跑一个健身教练工作流

```bash
# [任意终端] 提交工作流定义（WSL2 localhost 转发，Windows/WSL 都能连）
curl -X POST localhost:8080/api/v1/workflows \
  -H "Content-Type: application/json" \
  --data @src/test/resources/testdata/workflows/fitness-coach/fitness-coach.json

# [任意终端] 提交运行（同步返回最终状态；运行经 解析→存储→历史查询→检索→指标→路由→报告→保存 全链路）
curl -X POST localhost:8080/api/v1/runs \
  -H "Content-Type: application/json" \
  -d '{"workflowId":"fitness-coach","inputs":{"userLog":"20260808腿 史密斯深蹲 10/12 15/12 15/12 15/12","userId":"demo-user"}}'
```

运行结束后 `reports/fitness-coach/` 下生成 markdown 训练报告（agent 经 `save_report_md` 工具确定性落盘）。

### 灌健身知识库（RAG 检索用）

```bash
# [Windows] 生成灌库 JSON（node 在 Windows 侧）
node scripts/build_kb_json.js src/test/resources/kb/workout-kb

# [任意终端] 灌库
curl -X POST localhost:8080/api/v1/collections/workout_kb/documents \
  -H "Content-Type: application/json" \
  --data @src/test/resources/kb/workout-kb/workout-kb.json
```

## API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/workflows` | 提交工作流（解析 + 校验 + 入库） |
| GET | `/api/v1/workflows/{id}` | 查工作流（可 `?version=N` 指定版本） |
| GET | `/api/v1/workflows/{id}/versions` | 版本列表 |
| POST | `/api/v1/runs` | 提交运行（同步返回最终状态） |
| GET | `/api/v1/runs/{runId}` | 查运行状态（含各节点输出） |
| GET | `/api/v1/tools` | 工具列表 |
| POST | `/api/v1/tools` / `DELETE /api/v1/tools/{name}` | 动态注册 / 注销工具 |
| POST | `/api/v1/collections/{name}/documents` | 灌知识库文档 |

## 当前进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 环境骨架（docker-compose / Testcontainers） | ✅ |
| P1 | 领域模型 + DSL（图解析 / 校验 / 模板） | ✅ |
| P2 | 同步执行引擎（就绪度调度 / 条件路由 / 重试） | ✅ |
| P3 | Checkpoint 与恢复 | ✅ 已并入 P7（RedisCheckpointStore 落地；恢复由 at-least-once + 幂等覆盖） |
| P4 | LLM 接入 + 手写 Agentic Loop + 动态路由 | ✅ |
| P5 | 工具注册中心 + MCP | ✅ |
| P6 | RAG 节点（pgvector + 本地嵌入 + 灌库） | ✅ |
| P7 | 事件驱动改造（EventBus + Redis Streams） | ✅（T7.1-T7.6：事件驱动执行 + 幂等 + 死信） |
| P8 | Trace + Eval | ⬜ |
| P9 | Demo 打磨 | ⬜ |

## 目录结构

```
src/main/java/com/agentflow/
├── agent/          # LLM 网关抽象（LlmGateway）与 Spring AI 封装
├── core/           # 引擎核心：model / dsl / exec / loop / routing / state / store
├── tool/           # 工具注册中心、@AgentTool、内置工具、MCP 客户端
├── rag/            # 检索抽象（Retriever）与向量检索实现
├── runtime/        # checkpoint / queue（EventBus + Redis Streams）
└── api/            # REST API（workflows / runs / tools / collections）
scripts/            # 环境脚本（dev.sh）、知识库构建
src/test/resources/testdata/workflows/   # 示例工作流（健身教练助手等）
```
