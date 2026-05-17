package com.example.market.adapter.out.ratelimit

import com.example.market.application.port.out.TokenBucketRateLimiter
import com.example.market.application.port.out.TokenBucketRateLimiter.Decision
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * 단일 인스턴스용 in-memory token bucket — dev / 단위 테스트.
 *
 * 분산 환경에서는 인스턴스끼리 카운터가 안 맞으므로 [RedisTokenBucketRateLimiter] 사용.
 * dev 는 인스턴스 1대라 정확.
 *
 * 스레드 안전성은 [Bucket.tryConsume] 의 `synchronized` 로 확보. bucket 별 락이라
 * 다른 키 사이의 경합 없음. [ConcurrentHashMap.computeIfAbsent] 가 bucket 생성 자체를
 * 직렬화.
 */
@Component
@ConditionalOnProperty(
    name = ["market.cache.redis-enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class InMemoryTokenBucketRateLimiter(
    private val clock: Clock,
) : TokenBucketRateLimiter {

    private val buckets: ConcurrentMap<String, Bucket> = ConcurrentHashMap()

    override fun tryConsume(
        key: String,
        capacity: Int,
        refillTokens: Int,
        refillInterval: Duration,
    ): Decision {
        val b = buckets.computeIfAbsent(key) { Bucket(capacity.toLong(), clock.millis()) }
        return b.tryConsume(capacity, refillTokens, refillInterval.toMillis(), clock.millis())
    }

    internal class Bucket(initialTokens: Long, nowMs: Long) {
        private var tokens: Long = initialTokens
        private var lastRefillMs: Long = nowMs

        @Synchronized
        fun tryConsume(capacity: Int, refillTokens: Int, refillIvMs: Long, nowMs: Long): Decision {
            val elapsed = maxOf(0L, nowMs - lastRefillMs)
            val refilled = (elapsed * refillTokens) / refillIvMs
            if (refilled > 0) {
                tokens = minOf(capacity.toLong(), tokens + refilled)
                // 정수 토큰 만큼만 시간 진행 (Lua 스크립트와 동일 — 소수 토큰 누락 방지).
                lastRefillMs += (refilled * refillIvMs) / refillTokens
            }
            if (tokens > 0) {
                tokens--
                return Decision.allowed(tokens.toInt())
            }
            val msPerToken = (refillIvMs + refillTokens - 1) / refillTokens // ceil
            val progress = nowMs - lastRefillMs
            val retryMs = maxOf(0L, msPerToken - progress)
            return Decision.rejected(Duration.ofMillis(retryMs))
        }
    }
}
