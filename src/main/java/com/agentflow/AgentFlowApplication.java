package com.agentflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AgentFlow —— 轻量级 Agent DAG 编排引擎。
 *
 * <p>入口类。包结构约定见 docs/架构设计.md：
 * core（编排引擎，无框架依赖）/ runtime（状态与事件）/ agent（LLM 适配）/
 * tool（工具注册中心）/ memory / rag / trace / api。
 */
@SpringBootApplication
public class AgentFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentFlowApplication.class, args);
    }
}
