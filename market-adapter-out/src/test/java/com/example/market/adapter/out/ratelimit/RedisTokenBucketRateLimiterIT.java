package com.example.market.adapter.out.ratelimit;

import com.example.market.application.port.out.TokenBucketRateLimiter.Decision;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lua 스크립트 기반 token bucket 동작 검증 (실제 Redis Testcontainer).
 *
 * <p>Docker 없으면 자동 skip. atomic 검증은 동시 thread 진입으로 — capacity 만큼만 통과해야 함.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisTokenBucketRateLimiterIT {

    private static final RedisContainer REDIS = new RedisContainer(
            DockerImageName.parse("redis:7-alpine"));

    private static LettuceConnectionFactory cf;
    private static StringRedisTemplate redis;

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
    void flush() {
        redis.execute((org.springframework.data.redis.connection.RedisConnection c) -> {
            c.serverCommands().flushAll();
            return null;
        });
    }

    @Test
    void newKey_startsFull() {
        Clock clock = Clock.fixed(Instant.parse("2026-05-08T12:00:00Z"), ZoneOffset.UTC);
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(redis, clock);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume("u:p", 5, 1, Duration.ofSeconds(1)).allowed()).isTrue();
        }
        // 6번째는 거부
        Decision blocked = limiter.tryConsume("u:p", 5, 1, Duration.ofSeconds(1));
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.retryAfter().toMillis()).isPositive();
    }

    @Test
    void refillRespectsElapsedTime() {
        AdvancingClock clock = new AdvancingClock(Instant.parse("2026-05-08T12:00:00Z"));
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(redis, clock);

        // 통 비우기
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume("u:p", 3, 1, Duration.ofSeconds(1));
        }
        assertThat(limiter.tryConsume("u:p", 3, 1, Duration.ofSeconds(1)).allowed()).isFalse();

        // 1초 진행 → 1개 채워짐
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryConsume("u:p", 3, 1, Duration.ofSeconds(1)).allowed()).isTrue();
    }

    @Test
    void atomicConcurrentConsume_neverOverGrants() throws Exception {
        // capacity=10, 50 thread 가 동시에 진입 → 정확히 10건만 통과해야 함 (atomicity 보장)
        Clock clock = Clock.systemUTC();
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(redis, clock);

        int threads = 50;
        int capacity = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try {
            var futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        Decision d = limiter.tryConsume("u:burst",
                                capacity, 1, Duration.ofSeconds(1));
                        if (d.allowed()) allowed.incrementAndGet();
                        else rejected.incrementAndGet();
                        return null;
                    }))
                    .toList();
            start.countDown();
            for (var f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get()).isEqualTo(capacity);
        assertThat(rejected.get()).isEqualTo(threads - capacity);
    }

    @Test
    void differentKeysAreIndependent() {
        Clock clock = Clock.systemUTC();
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(redis, clock);

        for (int i = 0; i < 2; i++) {
            limiter.tryConsume("u1:p", 2, 1, Duration.ofSeconds(1));
        }
        assertThat(limiter.tryConsume("u1:p", 2, 1, Duration.ofSeconds(1)).allowed()).isFalse();
        // u2 는 별도 — 통 만땅
        assertThat(limiter.tryConsume("u2:p", 2, 1, Duration.ofSeconds(1)).allowed()).isTrue();
    }

    static final class AdvancingClock extends Clock {
        private Instant now;
        AdvancingClock(Instant start) { this.now = start; }
        void advance(Duration d) { this.now = this.now.plus(d); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        @Override public long millis() { return now.toEpochMilli(); }
    }
}
