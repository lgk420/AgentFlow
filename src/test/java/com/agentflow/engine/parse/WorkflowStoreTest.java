package com.agentflow.engine.parse;

import java.nio.charset.StandardCharsets;

import com.agentflow.engine.parse.GraphParser;
import com.agentflow.engine.model.definition.WorkflowDefinition;
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
 * T1.5 WorkflowStore 集成测试（Testcontainers redis）。
 *
 * <p>覆盖：保存后按 id 取最新版本、按 id+版本精确取、列版本升序、缺失返回 null、同版本重复保存幂等。
 * 前置同 RedisContainerBaselineTest（DOCKER_HOST 指向 WSL daemon，见 QA 03/13）。
 */
@Testcontainers
class WorkflowStoreTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private final GraphParser parser = new GraphParser(new ObjectMapper());
    private WorkflowStore store;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        // @Container 静态共享一个 Redis 实例，逐个测试前清库保证隔离
        template.execute((RedisCallback<Object>) connection -> {
            connection.flushDb();
            return null;
        });
        store = new WorkflowStore(template, parser, new ObjectMapper());
    }

    private WorkflowDefinition fixture(int version) throws Exception {
        String json = new String(
                getClass().getResourceAsStream("/testdata/workflows/legacy-fitness-coach/fitness-coach.json").readAllBytes(),
                StandardCharsets.UTF_8);
        WorkflowDefinition wf = parser.parse(json);
        wf.setVersion(version);
        return wf;
    }

    @Test
    void saveAndFindById_returnsLatestVersion() throws Exception {
        store.save(fixture(1));
        store.save(fixture(2));

        WorkflowDefinition latest = store.findById("fitness-coach");
        assertThat(latest).isNotNull();
        assertThat(latest.getVersion()).isEqualTo(2);
        assertThat(latest.getNodes()).containsKey("parse");
    }

    @Test
    void findByIdAndVersion_exactVersion() throws Exception {
        store.save(fixture(1));
        store.save(fixture(2));

        assertThat(store.findByIdAndVersion("fitness-coach", 1).getVersion()).isEqualTo(1);
        assertThat(store.findByIdAndVersion("fitness-coach", 2).getVersion()).isEqualTo(2);
    }

    @Test
    void findVersions_ascending() throws Exception {
        store.save(fixture(1));
        store.save(fixture(3));
        store.save(fixture(2));

        assertThat(store.findVersions("fitness-coach")).containsExactly(1, 2, 3);
    }

    @Test
    void findById_missing_returnsNull() {
        assertThat(store.findById("nope")).isNull();
        assertThat(store.findByIdAndVersion("nope", 1)).isNull();
    }

    @Test
    void sameIdSameVersion_upsert_idempotent() throws Exception {
        store.save(fixture(1));
        store.save(fixture(1)); // 同版本重复保存：覆盖且不新增版本

        assertThat(store.findVersions("fitness-coach")).containsExactly(1);
    }
}
