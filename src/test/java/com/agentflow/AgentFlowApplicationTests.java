package com.agentflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 基础上下文加载测试。
 *
 * <p>P0 阶段只验证 Spring 容器能正常装配（Redis 连接是懒加载的，
 * 即使本地 Redis 未启动，该测试也能通过）。
 *
 * <p>真正的 Testcontainers 容器化测试（连 WSL 里的 Docker）需要先配置
 * Windows 侧 DOCKER_HOST=tcp://localhost:2375，见 docs/qa/03-docker-compose与WSL环境.md。
 */
@SpringBootTest
class AgentFlowApplicationTests {

    @Test
    void contextLoads() {
    }
}
