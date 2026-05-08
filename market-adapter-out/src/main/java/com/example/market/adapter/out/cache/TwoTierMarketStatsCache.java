package com.example.market.adapter.out.cache;

import com.example.market.application.port.out.MarketStatsCache;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.marketdata.MarketStats;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Caffeine L1 + Redis L2 + cache stampede 방어 (ADR-0019).
 *
 * <h3>흐름</h3>
 * <pre>
 *   getOrCompute(sku, loader):
 *     L1 = Caffeine.getIfPresent(sku)
 *     if L1 hit AND not "soft expired" by XFetch:
 *       return L1
 *     else:
 *       L2 = redis.get(key)
 *       if L2 hit AND not "soft expired" by XFetch:
 *         L1.put(L2);  return L2
 *       else:                              # cold or stale
 *         if SETNX(lock_key, ttl=5s) succeeded:
 *           v = loader.get()                          # 진짜 DB 호출
 *           L2.SET(key, v, EX=10s);  L1.put(v);  DEL(lock_key)
 *           return v
 *         else:                              # 다른 thread/pod 가 갱신 중
 *           if 가지고 있는 stale 값이 있으면 그것을 반환
 *           else 짧게 기다린 뒤 재시도 (lock holder 가 곧 채워줌)
 * </pre>
 *
 * <h3>Probabilistic early refresh (XFetch)</h3>
 *
 * <p>TTL 가 임박하면 한 thread 만 미리 갱신을 시도하도록 stochastic 하게 유도. 지수 분포 변량
 * {@code -log(rand)} * {@code beta} * computeMs 만큼 만료를 *앞당겨* 본다 — 마지막 순간에 모두가
 * 동시에 만료를 보지 않고, 확률적으로 한두 명만 일찍 트리거. 자세한 분석은
 * "Optimal Probabilistic Cache Stampede Prevention" (VLDB 2015).</p>
 *
 * <h3>Lock 의 역할</h3>
 *
 * <p>XFetch 만으로도 stampede 가 거의 사라지지만, 100% 보장은 아니다. SETNX lock 이 마지막 가드 —
 * 그래도 동시에 두 thread 가 recompute 진입했다면 한 명만 진짜 loader 를 돌리고, 나머지는 lock
 * 해제 후 채워진 값을 본다. lock TTL 은 loader p99 + 여유 (5s 기본) — lock holder 가 죽어도
 * 그 시간 안에 자동 해제.</p>
 */
@Component
@ConditionalOnProperty(name = "market.cache.redis-enabled", havingValue = "true")
@Slf4j
public class TwoTierMarketStatsCache implements MarketStatsCache {

    private static final String CACHE_PREFIX = "market:stats:v1:";
    private static final String LOCK_PREFIX = "market:stats:lock:v1:";
    /** XFetch beta — 클수록 더 일찍 갱신 시도. 1.0 이 표준. */
    private static final double XFETCH_BETA = 1.0;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Cache<SkuId, CachedEntry> l1;
    private final Duration l1Ttl;
    private final Duration l2Ttl;
    private final Duration lockTtl;
    private final Duration loaderRetryWait;
    private final int loaderRetryAttempts;

    public TwoTierMarketStatsCache(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${market.cache.market-stats.l1-ttl-ms:1000}") long l1TtlMs,
            @Value("${market.cache.market-stats.l2-ttl-ms:10000}") long l2TtlMs,
            @Value("${market.cache.market-stats.l1-max-size:10000}") int l1MaxSize,
            @Value("${market.cache.market-stats.lock-ttl-ms:5000}") long lockTtlMs,
            @Value("${market.cache.market-stats.loader-retry-wait-ms:50}") long loaderRetryWaitMs,
            @Value("${market.cache.market-stats.loader-retry-attempts:3}") int loaderRetryAttempts) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.l1Ttl = Duration.ofMillis(l1TtlMs);
        this.l2Ttl = Duration.ofMillis(l2TtlMs);
        this.lockTtl = Duration.ofMillis(lockTtlMs);
        this.loaderRetryWait = Duration.ofMillis(loaderRetryWaitMs);
        this.loaderRetryAttempts = loaderRetryAttempts;
        // expireAfterWrite 는 *hard* 만료 — soft 만료 (XFetch) 는 우리가 entry 안에 expiresAt 을 두고 직접 검사.
        // L1 hard TTL 은 L2 TTL 과 같게 두어 "L1 에 있는데 L2 미스" 같은 비대칭이 안 나오게 함.
        this.l1 = Caffeine.newBuilder()
                .maximumSize(l1MaxSize)
                .expireAfterWrite(this.l2Ttl)
                .build();
    }

    @Override
    public MarketStats getOrCompute(SkuId key, Supplier<MarketStats> loader) {
        Instant now = clock.instant();
        CachedEntry l1Hit = l1.getIfPresent(key);
        if (l1Hit != null && !l1Hit.shouldRefreshEarly(now)) {
            return l1Hit.value;
        }

        // L2 (Redis) 조회 — l1 이 stale 이거나 비어있을 때.
        CachedEntry l2Hit = readL2(key, now);
        if (l2Hit != null && !l2Hit.shouldRefreshEarly(now)) {
            l1.put(key, l2Hit.withL1Expiry(now.plus(l1Ttl)));
            return l2Hit.value;
        }

        // 두 단 모두 stale 또는 miss → recompute. SETNX lock 으로 한 thread 만 진입.
        return recomputeWithStampedeGuard(key, loader, l1Hit, l2Hit, now);
    }

    @Override
    public void invalidate(SkuId key) {
        l1.invalidate(key);
        try {
            redis.delete(cacheKey(key));
        } catch (Exception e) {
            // Redis 가 죽어도 호출자는 영향받지 않게 — 다음 조회가 어차피 cold path.
            log.warn("Redis invalidate 실패 (무시) sku={} reason={}", key, e.getMessage());
        }
    }

    // ───────────── 내부 ─────────────

    private MarketStats recomputeWithStampedeGuard(SkuId key,
                                                   Supplier<MarketStats> loader,
                                                   CachedEntry l1Hit,
                                                   CachedEntry l2Hit,
                                                   Instant now) {
        String lockKey = LOCK_PREFIX + key.value();
        Boolean lockAcquired;
        try {
            lockAcquired = redis.opsForValue().setIfAbsent(lockKey, "1", lockTtl);
        } catch (Exception e) {
            // Redis 장애 — fail-open. loader 직접 호출.
            log.warn("Redis lock 시도 실패 — 캐시 우회하고 loader 직접 호출 sku={} reason={}",
                    key, e.getMessage());
            return loader.get();
        }

        if (Boolean.TRUE.equals(lockAcquired)) {
            try {
                MarketStats fresh = loader.get();
                Instant computedAt = clock.instant();
                long computeMs = Math.max(1, computedAt.toEpochMilli() - now.toEpochMilli());
                writeBoth(key, fresh, computedAt, computeMs);
                return fresh;
            } finally {
                try {
                    redis.delete(lockKey);
                } catch (Exception e) {
                    log.warn("lock 해제 실패 (TTL 만료에 의존) sku={} reason={}", key, e.getMessage());
                }
            }
        }

        // lock 못 잡음 — 누군가 갱신 중. stale 라도 있으면 그걸 반환 (사용자에게 응답 보장).
        if (l1Hit != null) {
            log.debug("stampede 보호: lock 못 잡아 L1 stale 반환 sku={}", key);
            return l1Hit.value;
        }
        if (l2Hit != null) {
            log.debug("stampede 보호: lock 못 잡아 L2 stale 반환 sku={}", key);
            return l2Hit.value;
        }

        // stale 도 없음 (cold start 동시 진입). 짧게 polling — lock holder 가 곧 채워줌.
        for (int i = 0; i < loaderRetryAttempts; i++) {
            sleep(loaderRetryWait);
            CachedEntry retry = readL2(key, clock.instant());
            if (retry != null) {
                l1.put(key, retry.withL1Expiry(clock.instant().plus(l1Ttl)));
                return retry.value;
            }
        }
        // 끝까지 못 받았으면 (lock holder 가 죽었거나) — 마지막 fallback 으로 직접 loader 호출.
        log.warn("lock holder polling 한도 초과 — fallback 으로 loader 직접 호출 sku={}", key);
        return loader.get();
    }

    private CachedEntry readL2(SkuId key, Instant now) {
        String json;
        try {
            json = redis.opsForValue().get(cacheKey(key));
        } catch (Exception e) {
            log.warn("Redis L2 읽기 실패 — miss 처리 sku={} reason={}", key, e.getMessage());
            return null;
        }
        if (json == null) return null;
        try {
            CachedEnvelope env = objectMapper.readValue(json, CachedEnvelope.class);
            MarketStats v = env.value().toDomain();
            return new CachedEntry(v, env.expiresAt(), env.computeDurationMs());
        } catch (JsonProcessingException e) {
            // 직렬화 형식이 바뀐 경우 — 그냥 miss 로 처리. 다음 loader 호출이 새 형식으로 다시 채움.
            log.info("L2 json 디코딩 실패 (스키마 변경 가능성, miss 로 처리) sku={}", key);
            return null;
        }
    }

    private void writeBoth(SkuId key, MarketStats v, Instant computedAt, long computeMs) {
        Instant expiresAt = computedAt.plus(l2Ttl);
        CachedEnvelope env = new CachedEnvelope(MarketStatsCacheRecord.from(v), expiresAt, computeMs);
        try {
            String json = objectMapper.writeValueAsString(env);
            redis.opsForValue().set(cacheKey(key), json, l2Ttl);
        } catch (Exception e) {
            // L2 쓰기 실패도 fail-open. L1 만으로도 다음 호출은 hit.
            log.warn("L2 쓰기 실패 (L1 만 사용) sku={} reason={}", key, e.getMessage());
        }
        l1.put(key, new CachedEntry(v, expiresAt, computeMs).withL1Expiry(computedAt.plus(l1Ttl)));
    }

    private static String cacheKey(SkuId key) {
        return CACHE_PREFIX + key.value();
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * L1 entry. {@code shouldRefreshEarly} 가 XFetch 알고리즘 — 만료가 임박할수록
     * (확률적으로) 일찍 갱신을 시도. computeMs 가 클수록 (= 무거운 쿼리) 더 일찍 시도.
     */
    static final class CachedEntry {
        final MarketStats value;
        final Instant expiresAt;
        final long computeMs;
        final Instant l1ExpiresAt;

        CachedEntry(MarketStats value, Instant expiresAt, long computeMs) {
            this(value, expiresAt, computeMs, expiresAt);
        }

        private CachedEntry(MarketStats value, Instant expiresAt, long computeMs, Instant l1ExpiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
            this.computeMs = computeMs;
            this.l1ExpiresAt = l1ExpiresAt;
        }

        CachedEntry withL1Expiry(Instant l1Expiry) {
            // L1 보관 기간을 짧게 잡되, L2 hard expiry 는 그대로 — soft refresh 판단에 사용.
            return new CachedEntry(value, expiresAt, computeMs, l1Expiry);
        }

        boolean shouldRefreshEarly(Instant now) {
            // L1 hard 만료 우선
            if (!now.isBefore(l1ExpiresAt)) return true;
            // expiresAt - 지수 분포 변량 만큼 앞으로 끌어당김.
            // Optimal Probabilistic Cache Stampede Prevention (VLDB 2015).
            double rand = Math.max(1e-9, ThreadLocalRandom.current().nextDouble());
            double earlyMs = computeMs * XFETCH_BETA * -Math.log(rand);
            Instant earlyDeadline = expiresAt.minusMillis((long) earlyMs);
            return !now.isBefore(earlyDeadline);
        }
    }

    /** Redis 에 저장되는 envelope. */
    record CachedEnvelope(
            MarketStatsCacheRecord value,
            Instant expiresAt,
            long computeDurationMs
    ) {}
}
