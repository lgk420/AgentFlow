package com.agentflow;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 · T0.4 Testcontainers 基线用例。
 *
 * <p>验证 Windows 侧 JVM 能经 DOCKER_HOST 直连 WSL 里的 docker daemon：
 * 让 Testcontainers 拉起一个一次性 redis:7-alpine 容器，写入再读回。
 *
 * <p>前置（见 docs/qa/03、docs/qa/13）：
 * <ul>
 *   <li>WSL 里 daemon 已暴露为 tcp://0.0.0.0:2375</li>
 *   <li>本机 DOCKER_HOST=tcp://localhost:2375</li>
 * </ul>
 */
@Testcontainers
class RedisContainerBaselineTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @Test
    void redisReadWrite() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();

        template.opsForValue().set("baseline", "ok");
        assertThat(template.opsForValue().get("baseline")).isEqualTo("ok");
    }
}
