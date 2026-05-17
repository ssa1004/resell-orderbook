package com.example.market.adapter.out.ratelimit

import com.example.market.application.port.out.AdminRateLimiter
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 단일 인스턴스용 in-memory admin rate limiter — dev 또는 Redis 가 꺼진 환경.
 *
 * Redis 어댑터와 같은 policy / scope key prefix 를 사용해 정책 일관. 분산 환경에서는
 * 인스턴스끼리 카운터가 맞지 않으므로 prod 는 [RedisAdminRateLimiter] 사용.
 */
@Component
@ConditionalOnProperty(name = ["market.cache.redis-enabled"], havingValue = "false", matchIfMissing = true)
class InMemoryAdminRateLimiter(
    private val clock: Clock,
) : AdminRateLimiter {

    private val buckets = ConcurrentHashMap<String, Bucket>()

    override fun tryAcquire(scope: String, actorKey: String): AdminRateLimiter.Decision {
        val (capacity, refillPerMin) = policyFor(scope)
        val bucket = buckets.computeIfAbsent("$scope|$actorKey") { Bucket(capacity, clock.millis()) }
        return bucket.tryConsume(capacity, refillPerMin, REFILL_WINDOW_MS, clock.millis())
    }

    private fun policyFor(scope: String): Pair<Int, Int> = when (scope) {
        "dlq.read" -> 120 to 120
        "dlq.write" -> 60 to 60
        "dlq.bulk" -> 10 to 10
        else -> 60 to 60
    }

    private class Bucket(initialTokens: Int, nowMs: Long) {
        private var tokens: Long = initialTokens.toLong()
        private var lastRefillMs: Long = nowMs

        @Synchronized
        fun tryConsume(capacity: Int, refillTokens: Int, refillIvMs: Long, nowMs: Long): AdminRateLimiter.Decision {
            val elapsed = (nowMs - lastRefillMs).coerceAtLeast(0)
            val refilled = (elapsed * refillTokens) / refillIvMs
            if (refilled > 0) {
                tokens = minOf(capacity.toLong(), tokens + refilled)
                lastRefillMs += (refilled * refillIvMs) / refillTokens
            }
            return if (tokens > 0) {
                tokens--
                AdminRateLimiter.Decision.allow()
            } else {
                val msPerToken = (refillIvMs + refillTokens - 1) / refillTokens
                val progress = nowMs - lastRefillMs
                val retryMs = (msPerToken - progress).coerceAtLeast(0)
                AdminRateLimiter.Decision.reject(Duration.ofMillis(retryMs))
            }
        }
    }

    companion object {
        private const val REFILL_WINDOW_MS: Long = 60_000L
    }
}
