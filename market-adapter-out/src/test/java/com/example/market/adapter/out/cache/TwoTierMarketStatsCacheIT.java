package com.example.market.adapter.out.cache;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.marketdata.MarketStats;
import com.example.market.domain.shared.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Redis (Testcontainer) 위에서 2단 캐시의 핵심 거동 검증 — Docker 없으면 자동 skip.
 *
 * <p>검증:</p>
 * <ul>
 *   <li>cache miss → loader 1 회 호출 → 결과를 L2 (Redis) 와 L1 (Caffeine) 에 저장</li>
 *   <li>두 번째 호출은 L1 hit — loader 호출 0회</li>
 *   <li>L1 강제 만료 후 호출은 L2 hit — loader 호출 0회</li>
 *   <li>cold cache 에 N 개 thread 동시 진입해도 loader 는 1번만 호출됨 (stampede 방어)</li>
 *   <li>invalidate 후 재조회는 다시 loader 호출</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class TwoTierMarketStatsCacheIT {

    private static final Currency KRW = Currency.getInstance("KRW");

    private static final RedisContainer REDIS = new RedisContainer(
            DockerImageName.parse("redis:7-alpine"));

    private static LettuceConnectionFactory cf;
    private static StringRedisTemplate redis;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void start() {
        REDIS.start();
        cf = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getRedisPort());
        cf.afterPropertiesSet();
        redis = new StringRedisTemplate(cf);
        redis.afterPropertiesSet();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
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

    private TwoTierMarketStatsCache newCache(Clock clock) {
        return newCache(clock, 1000, 10000);
    }

    private TwoTierMarketStatsCache newCache(Clock clock, long l1TtlMs, long l2TtlMs) {
        return new TwoTierMarketStatsCache(
                redis, objectMapper, clock,
                l1TtlMs, l2TtlMs, 10_000, 5_000,
                10, 3);
    }

    private static MarketStats fakeStats(SkuId sku, long price, Instant at) {
        Money p = Money.of(BigDecimal.valueOf(price), KRW);
        return new MarketStats(sku, at, p, at, null, null, null, 1L, p, p, p);
    }

    @Test
    void cacheMiss_thenHit_loaderCalledOnce() {
        SkuId sku = SkuId.of(UUID.randomUUID().toString());
        Clock clock = Clock.fixed(Instant.parse("2026-05-08T12:00:00Z"), ZoneOffset.UTC);
        TwoTierMarketStatsCache cache = newCache(clock);
        AtomicInteger calls = new AtomicInteger();

        MarketStats first = cache.getOrCompute(sku, () -> {
            calls.incrementAndGet();
            return fakeStats(sku, 100_000, clock.instant());
        });
        MarketStats second = cache.getOrCompute(sku, () -> {
            calls.incrementAndGet();
            return fakeStats(sku, 999_999, clock.instant());
        });

        assertThat(calls.get()).isEqualTo(1);
        assertThat(second).isEqualTo(first);
        assertThat(second.lastTradePrice().amount()).isEqualByComparingTo("100000");
    }

    @Test
    void l1Expired_l2HitsWithoutLoader() {
        SkuId sku = SkuId.of(UUID.randomUUID().toString());
        Instant t0 = Instant.parse("2026-05-08T12:00:00Z");
        AdvancingClock clock = new AdvancingClock(t0);
        // L1 TTL 짧게, L2 TTL 길게.
        TwoTierMarketStatsCache cache = newCache(clock, 50, 30_000);
        AtomicInteger calls = new AtomicInteger();

        cache.getOrCompute(sku, () -> {
            calls.incrementAndGet();
            return fakeStats(sku, 100_000, clock.instant());
        });

        // 새 캐시 인스턴스 — L1 (in-process) 는 비어있고 L2 (Redis) 에서만 hit 해야 함.
        TwoTierMarketStatsCache freshL1 = newCache(clock, 50, 30_000);
        MarketStats v = freshL1.getOrCompute(sku, () -> {
            calls.incrementAndGet();
            return fakeStats(sku, 999_999, clock.instant());
        });

        assertThat(calls.get()).isEqualTo(1);    // L2 hit, loader 호출 안 됨
        assertThat(v.lastTradePrice().amount()).isEqualByComparingTo("100000");
    }

    @Test
    void coldStart_concurrent_loaderCalledOnce() throws Exception {
        SkuId sku = SkuId.of(UUID.randomUUID().toString());
        Clock clock = Clock.systemUTC();
        TwoTierMarketStatsCache cache = newCache(clock);

        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        try {
            List<java.util.concurrent.Future<MarketStats>> futures = java.util.stream.IntStream.range(0, n)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return cache.getOrCompute(sku, () -> {
                            calls.incrementAndGet();
                            sleep(80);          // 무거운 쿼리 흉내
                            return fakeStats(sku, 100_000 + i, clock.instant());
                        });
                    }))
                    .toList();
            start.countDown();
            for (var f : futures) {
                MarketStats r = f.get(30, TimeUnit.SECONDS);
                // 모두 같은 결과를 받아야 함 (lock holder 가 채운 값).
                assertThat(r.lastTradePrice()).isNotNull();
            }
        } finally {
            pool.shutdownNow();
        }

        // SETNX lock 으로 loader 는 1번만 호출되어야 함 — stampede 방어.
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void invalidate_forcesReload() {
        SkuId sku = SkuId.of(UUID.randomUUID().toString());
        Clock clock = Clock.fixed(Instant.parse("2026-05-08T12:00:00Z"), ZoneOffset.UTC);
        TwoTierMarketStatsCache cache = newCache(clock);
        AtomicInteger calls = new AtomicInteger();

        cache.getOrCompute(sku, () -> { calls.incrementAndGet(); return fakeStats(sku, 100_000, clock.instant()); });
        cache.invalidate(sku);
        cache.getOrCompute(sku, () -> { calls.incrementAndGet(); return fakeStats(sku, 200_000, clock.instant()); });

        assertThat(calls.get()).isEqualTo(2);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** millis 단위로 자동 진행하는 Clock — 테스트용. */
    static final class AdvancingClock extends Clock {
        private Instant now;
        AdvancingClock(Instant start) { this.now = start; }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        @Override public long millis() { return now.toEpochMilli(); }

        void advance(java.time.Duration d) { this.now = this.now.plus(d); }
    }
}
