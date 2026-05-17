package com.example.market.adapter.out.cache

import com.example.market.application.port.out.MarketStatsCache
import com.example.market.domain.catalog.SkuId
import com.example.market.domain.marketdata.MarketStats
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom
import java.util.function.Supplier
import kotlin.math.ln
import kotlin.math.max

/**
 * Caffeine L1 + Redis L2 + cache stampede 방어 (ADR-0019).
 *
 * ### 흐름
 * ```
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
 * ```
 *
 * ### Probabilistic early refresh (XFetch)
 *
 * TTL 가 임박하면 한 thread 만 미리 갱신을 시도하도록 stochastic 하게 유도. 지수 분포 변량
 * `-log(rand)` * `beta` * computeMs 만큼 만료를 *앞당겨* 본다 — 마지막 순간에 모두가
 * 동시에 만료를 보지 않고, 확률적으로 한두 명만 일찍 트리거. 자세한 분석은
 * "Optimal Probabilistic Cache Stampede Prevention" (VLDB 2015).
 *
 * ### Lock 의 역할
 *
 * XFetch 만으로도 stampede 가 거의 사라지지만, 100% 보장은 아니다. SETNX lock 이 마지막 가드 —
 * 그래도 동시에 두 thread 가 recompute 진입했다면 한 명만 진짜 loader 를 돌리고, 나머지는 lock
 * 해제 후 채워진 값을 본다. lock TTL 은 loader p99 + 여유 (5s 기본) — lock holder 가 죽어도
 * 그 시간 안에 자동 해제.
 */
@Component
@ConditionalOnProperty(name = ["market.cache.redis-enabled"], havingValue = "true")
class TwoTierMarketStatsCache(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    @Value("\${market.cache.market-stats.l1-ttl-ms:1000}") l1TtlMs: Long,
    @Value("\${market.cache.market-stats.l2-ttl-ms:10000}") l2TtlMs: Long,
    @Value("\${market.cache.market-stats.l1-max-size:10000}") l1MaxSize: Int,
    @Value("\${market.cache.market-stats.lock-ttl-ms:5000}") lockTtlMs: Long,
    @Value("\${market.cache.market-stats.loader-retry-wait-ms:50}") loaderRetryWaitMs: Long,
    @Value("\${market.cache.market-stats.loader-retry-attempts:3}") private val loaderRetryAttempts: Int,
) : MarketStatsCache {

    private val log = LoggerFactory.getLogger(javaClass)

    private val l1Ttl: Duration = Duration.ofMillis(l1TtlMs)
    private val l2Ttl: Duration = Duration.ofMillis(l2TtlMs)
    private val lockTtl: Duration = Duration.ofMillis(lockTtlMs)
    private val loaderRetryWait: Duration = Duration.ofMillis(loaderRetryWaitMs)

    // expireAfterWrite 는 hard 만료 — soft 만료 (XFetch) 는 우리가 entry 안에 expiresAt 을 두고 직접 검사.
    // L1 hard TTL 은 L2 TTL 과 같게 두어 "L1 에 있는데 L2 미스" 같은 비대칭이 안 나오게 함.
    private val l1: Cache<SkuId, CachedEntry> = Caffeine.newBuilder()
        .maximumSize(l1MaxSize.toLong())
        .expireAfterWrite(l2Ttl)
        .build()

    /**
     * cross-pod L1 evict broadcaster (ADR-0022). null 이면 단일 인스턴스 (또는 broadcast 비활성)
     * 모드 — L1 TTL 만으로 stale 시간 보장. setter 주입 — Configuration 의 후속 단계에서 wire
     * (생성자 의존성으로 두면 broadcast 비활성 환경에서 빈 graph 가 깨짐).
     */
    private var invalidationPublisher: CacheInvalidationPublisher? = null

    /** [CacheInvalidationConfiguration] 가 활성일 때만 호출됨. */
    fun setInvalidationPublisher(publisher: CacheInvalidationPublisher) {
        this.invalidationPublisher = publisher
    }

    override fun getOrCompute(key: SkuId, loader: Supplier<MarketStats>): MarketStats {
        val now = clock.instant()
        val l1Hit = l1.getIfPresent(key)
        if (l1Hit != null && !l1Hit.shouldRefreshEarly(now)) {
            return l1Hit.value
        }

        // L2 (Redis) 조회 — l1 이 stale 이거나 비어있을 때.
        val l2Hit = readL2(key, now)
        if (l2Hit != null && !l2Hit.shouldRefreshEarly(now)) {
            l1.put(key, l2Hit.withL1Expiry(now.plus(l1Ttl)))
            return l2Hit.value
        }

        // 두 단 모두 stale 또는 miss → recompute. SETNX lock 으로 한 thread 만 진입.
        return recomputeWithStampedeGuard(key, loader, l1Hit, l2Hit, now)
    }

    override fun invalidate(key: SkuId) {
        l1.invalidate(key)
        try {
            redis.delete(cacheKey(key))
        } catch (e: Exception) {
            // Redis 가 죽어도 호출자는 영향받지 않게 — 다음 조회가 어차피 cold path.
            log.warn("Redis invalidate 실패 (무시) sku={} reason={}", key, e.message)
        }
        broadcastInvalidate(key)
    }

    /**
     * 외부 (다른 pod) 가 publish 한 invalidate 메시지를 받아 자기 L1 만 evict. L2 (Redis) 는 전 pod
     * 가 공유하므로 한 pod 가 이미 갱신했고, 메시지의 의도는 *L1 캐시 일관성 유지* 다.
     *
     * 본 메서드는 [CacheInvalidationSubscriber] 의 `onInvalidate` 콜백으로 등록된다.
     */
    fun evictL1Only(key: SkuId) {
        l1.invalidate(key)
    }

    /** broadcaster 가 등록된 경우 invalidate 메시지를 publish. 실패는 swallow (TTL 안전망). */
    private fun broadcastInvalidate(key: SkuId) {
        invalidationPublisher?.publish(key.value.toString())
    }

    private fun recomputeWithStampedeGuard(
        key: SkuId,
        loader: Supplier<MarketStats>,
        l1Hit: CachedEntry?,
        l2Hit: CachedEntry?,
        now: Instant,
    ): MarketStats {
        val lockKey = LOCK_PREFIX + key.value
        val lockAcquired: Boolean? = try {
            redis.opsForValue().setIfAbsent(lockKey, "1", lockTtl)
        } catch (e: Exception) {
            // Redis 장애 — fail-open. loader 직접 호출.
            log.warn(
                "Redis lock 시도 실패 — 캐시 우회하고 loader 직접 호출 sku={} reason={}",
                key, e.message,
            )
            return loader.get()
        }

        if (lockAcquired == true) {
            try {
                val fresh = loader.get()
                val computedAt = clock.instant()
                val computeMs = max(1L, computedAt.toEpochMilli() - now.toEpochMilli())
                writeBoth(key, fresh, computedAt, computeMs)
                return fresh
            } finally {
                try {
                    redis.delete(lockKey)
                } catch (e: Exception) {
                    log.warn("lock 해제 실패 (TTL 만료에 의존) sku={} reason={}", key, e.message)
                }
            }
        }

        // lock 못 잡음 — 누군가 갱신 중. stale 라도 있으면 그걸 반환 (사용자에게 응답 보장).
        if (l1Hit != null) {
            log.debug("stampede 보호: lock 못 잡아 L1 stale 반환 sku={}", key)
            return l1Hit.value
        }
        if (l2Hit != null) {
            log.debug("stampede 보호: lock 못 잡아 L2 stale 반환 sku={}", key)
            return l2Hit.value
        }

        // stale 도 없음 (cold start 동시 진입). 짧게 polling — lock holder 가 곧 채워줌.
        for (i in 0 until loaderRetryAttempts) {
            sleep(loaderRetryWait)
            val retry = readL2(key, clock.instant())
            if (retry != null) {
                l1.put(key, retry.withL1Expiry(clock.instant().plus(l1Ttl)))
                return retry.value
            }
        }
        // 끝까지 못 받았으면 (lock holder 가 죽었거나) — 마지막 fallback 으로 직접 loader 호출.
        log.warn("lock holder polling 한도 초과 — fallback 으로 loader 직접 호출 sku={}", key)
        return loader.get()
    }

    private fun readL2(key: SkuId, now: Instant): CachedEntry? {
        val json: String? = try {
            redis.opsForValue().get(cacheKey(key))
        } catch (e: Exception) {
            log.warn("Redis L2 읽기 실패 — miss 처리 sku={} reason={}", key, e.message)
            return null
        }
        if (json == null) return null
        return try {
            val env = objectMapper.readValue(json, CachedEnvelope::class.java)
            val v = env.value.toDomain()
            CachedEntry(v, env.expiresAt, env.computeDurationMs)
        } catch (e: JsonProcessingException) {
            // 직렬화 형식이 바뀐 경우 — 그냥 miss 로 처리. 다음 loader 호출이 새 형식으로 다시 채움.
            log.info("L2 json 디코딩 실패 (스키마 변경 가능성, miss 로 처리) sku={}", key)
            null
        }
    }

    private fun writeBoth(key: SkuId, v: MarketStats, computedAt: Instant, computeMs: Long) {
        val expiresAt = computedAt.plus(l2Ttl)
        val env = CachedEnvelope(MarketStatsCacheRecord.from(v), expiresAt, computeMs)
        try {
            val json = objectMapper.writeValueAsString(env)
            redis.opsForValue().set(cacheKey(key), json, l2Ttl)
        } catch (e: Exception) {
            // L2 쓰기 실패도 fail-open. L1 만으로도 다음 호출은 hit.
            log.warn("L2 쓰기 실패 (L1 만 사용) sku={} reason={}", key, e.message)
        }
        l1.put(key, CachedEntry(v, expiresAt, computeMs).withL1Expiry(computedAt.plus(l1Ttl)))
        // 다른 pod 의 L1 stale 을 즉시 무효화 — 본 pod 는 이미 새 값을 가졌고, 다른 pod 는 메시지를
        // 받아 자기 L1 만 evict 하면 다음 조회 시 L2 hit (== 본 pod 가 방금 쓴 값) 으로 즉시 일관성 회복.
        broadcastInvalidate(key)
    }

    private fun sleep(d: Duration) {
        try {
            Thread.sleep(d.toMillis())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * L1 entry. `shouldRefreshEarly` 가 XFetch 알고리즘 — 만료가 임박할수록
     * (확률적으로) 일찍 갱신을 시도. computeMs 가 클수록 (= 무거운 쿼리) 더 일찍 시도.
     */
    internal class CachedEntry(
        val value: MarketStats,
        val expiresAt: Instant,
        val computeMs: Long,
        val l1ExpiresAt: Instant = expiresAt,
    ) {
        fun withL1Expiry(l1Expiry: Instant): CachedEntry =
            // L1 보관 기간을 짧게 잡되, L2 hard expiry 는 그대로 — soft refresh 판단에 사용.
            CachedEntry(value, expiresAt, computeMs, l1Expiry)

        fun shouldRefreshEarly(now: Instant): Boolean {
            // L1 hard 만료 우선
            if (!now.isBefore(l1ExpiresAt)) return true
            // expiresAt - 지수 분포 변량 만큼 앞으로 끌어당김.
            // Optimal Probabilistic Cache Stampede Prevention (VLDB 2015).
            val rand = max(1e-9, ThreadLocalRandom.current().nextDouble())
            val earlyMs = computeMs * XFETCH_BETA * -ln(rand)
            val earlyDeadline = expiresAt.minusMillis(earlyMs.toLong())
            return !now.isBefore(earlyDeadline)
        }
    }

    /** Redis 에 저장되는 envelope. */
    @JvmRecord
    internal data class CachedEnvelope(
        val value: MarketStatsCacheRecord,
        val expiresAt: Instant,
        val computeDurationMs: Long,
    )

    companion object {
        private const val CACHE_PREFIX = "market:stats:v1:"
        private const val LOCK_PREFIX = "market:stats:lock:v1:"

        /** XFetch beta — 클수록 더 일찍 갱신 시도. 1.0 이 표준. */
        private const val XFETCH_BETA = 1.0

        private fun cacheKey(key: SkuId): String = CACHE_PREFIX + key.value
    }
}
