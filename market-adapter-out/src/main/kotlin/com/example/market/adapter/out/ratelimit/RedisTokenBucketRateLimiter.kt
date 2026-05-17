package com.example.market.adapter.out.ratelimit

import com.example.market.application.port.out.TokenBucketRateLimiter
import com.example.market.application.port.out.TokenBucketRateLimiter.Decision
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.scripting.support.ResourceScriptSource
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * Lua 스크립트 기반 atomic token bucket (ADR-0020).
 *
 * Redis 의 `EVAL` 한 번에 read → refill → try-consume → write 전부 — 동시에 도착한
 * 두 요청이 같은 토큰을 두 번 빼는 race 없음.
 *
 * Redis 가 죽으면 [tryConsume] 는 throw 하지 않고 *fail-open* (= allowed) 로 fallback —
 * 가용성 우선. 호출 측은 일반 응답 흐름 유지. 보안 민감 endpoint 는 별도 정책 필요 (ADR-0020).
 */
@Component
@ConditionalOnProperty(name = ["market.cache.redis-enabled"], havingValue = "true")
class RedisTokenBucketRateLimiter(
    private val redis: StringRedisTemplate,
    private val clock: Clock,
) : TokenBucketRateLimiter {

    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("UNCHECKED_CAST")
    private val script: RedisScript<List<*>> = run {
        val s = DefaultRedisScript<List<*>>()
        s.setScriptSource(
            ResourceScriptSource(ClassPathResource("scripts/ratelimit/token_bucket.lua")),
        )
        s.setResultType(List::class.java as Class<List<*>>)
        s
    }

    override fun tryConsume(
        key: String,
        capacity: Int,
        refillTokens: Int,
        refillInterval: Duration,
    ): Decision {
        require(capacity > 0 && refillTokens > 0 && !refillInterval.isZero && !refillInterval.isNegative) {
            "capacity / refillTokens / refillInterval must be positive — got $capacity / $refillTokens / $refillInterval"
        }
        val nowMs = clock.millis()
        return try {
            @Suppress("UNCHECKED_CAST")
            val result = redis.execute(
                script,
                listOf(KEY_PREFIX + key),
                capacity.toString(),
                refillTokens.toString(),
                refillInterval.toMillis().toString(),
                nowMs.toString(),
            ) as List<Long>
            val allowed = result[0]
            val remaining = result[1].toInt()
            val retryAfterMs = result[2]
            if (allowed == 1L) Decision.allowed(remaining)
            else Decision.rejected(Duration.ofMillis(retryAfterMs))
        } catch (e: Exception) {
            // fail-open: Redis 장애가 사용자 경험을 깨지 않도록.
            log.warn("rate limiter 실패 — fail-open 로 통과 key={} reason={}", key, e.message)
            Decision.allowed(capacity)
        }
    }

    companion object {
        /** 모든 bucket 키 prefix — 운영에서 다른 도메인 키와 섞이지 않게. */
        private const val KEY_PREFIX = "rl:tb:"
    }
}
