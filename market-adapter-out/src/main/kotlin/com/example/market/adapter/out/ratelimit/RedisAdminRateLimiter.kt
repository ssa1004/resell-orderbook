package com.example.market.adapter.out.ratelimit

import com.example.market.application.port.out.AdminRateLimiter
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.scripting.support.ResourceScriptSource
import org.springframework.stereotype.Component

/**
 * 운영자 admin 콘솔 전용 Lua atomic token bucket (ADR-0028 + ADR-0020).
 *
 * 사용자 facing 의 [com.example.market.adapter.out.ratelimit.RedisTokenBucketRateLimiter]
 * 와 같은 알고리즘이지만 prefix / scope 가 분리되어 정책 (RPS) 도 별도. scope = "dlq.read" /
 * "dlq.write" / "dlq.bulk" 가 각각 다른 capacity / refillRate 를 받는다.
 *
 * **fail-closed**: Redis 가 죽으면 본 limiter 는 *거부* 한다. 사용자 facing limiter 는 fail-open
 * (가용성 우선) 이지만, admin bulk 액션은 의도치 않게 호출되었을 때 PG 가 두 번 호출되는
 * 사고가 크기 때문에 보수적으로 잠근다.
 *
 * 기본 정책 (yml 로 override 가능):
 *
 * - read : capacity=120, refill=120/min
 * - write: capacity=60,  refill=60/min
 * - bulk : capacity=10,  refill=10/min
 *
 * 한 사람의 운영자가 폭주해도 RPS 가 묶이지만, 다른 운영자는 별 key prefix 라 영향을 받지
 * 않는다.
 */
@Component
@ConditionalOnProperty(name = ["market.cache.redis-enabled"], havingValue = "true")
class RedisAdminRateLimiter(
    private val redis: StringRedisTemplate,
    private val clock: Clock,
    @Value("\${market.admin.ratelimit.read.capacity:120}") private val readCapacity: Int,
    @Value("\${market.admin.ratelimit.read.refill-per-min:120}") private val readRefillPerMin: Int,
    @Value("\${market.admin.ratelimit.write.capacity:60}") private val writeCapacity: Int,
    @Value("\${market.admin.ratelimit.write.refill-per-min:60}") private val writeRefillPerMin: Int,
    @Value("\${market.admin.ratelimit.bulk.capacity:10}") private val bulkCapacity: Int,
    @Value("\${market.admin.ratelimit.bulk.refill-per-min:10}") private val bulkRefillPerMin: Int,
) : AdminRateLimiter {

    private val log = LoggerFactory.getLogger(javaClass)
    private val script: RedisScript<List<*>>

    init {
        val s = DefaultRedisScript<List<*>>()
        s.setScriptSource(ResourceScriptSource(ClassPathResource("scripts/ratelimit/admin_token_bucket.lua")))
        @Suppress("UNCHECKED_CAST")
        s.setResultType(List::class.java as Class<List<*>>)
        this.script = s
    }

    override fun tryAcquire(scope: String, actorKey: String): AdminRateLimiter.Decision {
        val (capacity, refillPerMin) = policyFor(scope)
        val nowMs = clock.millis()
        val refillIntervalMs = REFILL_WINDOW.toMillis()
        return try {
            @Suppress("UNCHECKED_CAST")
            val result = redis.execute(
                script,
                listOf("$KEY_PREFIX:$scope:$actorKey"),
                capacity.toString(), refillPerMin.toString(),
                refillIntervalMs.toString(), nowMs.toString(),
            ) as List<Long>
            if (result[0] == 1L) {
                AdminRateLimiter.Decision.allow()
            } else {
                AdminRateLimiter.Decision.reject(Duration.ofMillis(result[2]))
            }
        } catch (e: Exception) {
            // fail-closed — admin 액션을 안전하게 잠근다 (사용자 facing 과 정책이 다른 핵심).
            log.warn("admin rate limiter fail-closed scope={} actor={} reason={}",
                scope, actorKey, e.message)
            AdminRateLimiter.Decision.reject(FAIL_CLOSED_RETRY_AFTER)
        }
    }

    private fun policyFor(scope: String): Pair<Int, Int> = when (scope) {
        "dlq.read" -> readCapacity to readRefillPerMin
        "dlq.write" -> writeCapacity to writeRefillPerMin
        "dlq.bulk" -> bulkCapacity to bulkRefillPerMin
        else -> writeCapacity to writeRefillPerMin // 보수적 default
    }

    companion object {
        private const val KEY_PREFIX = "admin"
        private val REFILL_WINDOW: Duration = Duration.ofMinutes(1)
        private val FAIL_CLOSED_RETRY_AFTER: Duration = Duration.ofSeconds(10)
    }
}
