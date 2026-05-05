package com.example.market.adapter.out.persistence.idempotency;

import com.example.market.application.port.out.IdempotencyKeyStore;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 Redis (Testcontainer) 위에서 SETNX 멱등성 lock 동작 검증.
 * Docker 없으면 자동 skip.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisIdempotencyKeyStoreIT {

    private static final RedisContainer REDIS = new RedisContainer(
            DockerImageName.parse("redis:7-alpine"));

    private static LettuceConnectionFactory cf;
    private static StringRedisTemplate redis;
    private RedisIdempotencyKeyStore store;

    @BeforeAll
    static void start() {
        REDIS.start();
        cf = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getRedisPort());
        cf.afterPropertiesSet();
        redis = new StringRedisTemplate(cf);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stop() {
        if (cf != null) cf.destroy();
        if (REDIS.isRunning()) REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        redis.execute((org.springframework.data.redis.connection.RedisConnection c) -> {
            c.serverCommands().flushAll();
            return null;
        });
        store = new RedisIdempotencyKeyStore(redis);
        ReflectionTestUtils.setField(store, "ttlHours", 24L);
    }

    @Test
    void firstAcquire_succeeds() {
        store.acquireOrThrow("k1");
    }

    @Test
    void duplicateAcquire_throws() {
        store.acquireOrThrow("dup");
        assertThatThrownBy(() -> store.acquireOrThrow("dup"))
                .isInstanceOf(IdempotencyKeyStore.DuplicateRequestException.class);
    }

    @Test
    void differentKeys_independent() {
        store.acquireOrThrow("a");
        store.acquireOrThrow("b");
        store.acquireOrThrow("c");
    }
}
