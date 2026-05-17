package com.example.market.adapter.out.persistence.idempotency

import com.example.market.application.port.out.IdempotencyKeyStore
import com.example.market.application.port.out.IdempotencyKeyStore.DuplicateRequestException
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Redis SETNX 기반 멱등성 키. 분산 환경 prod.
 *
 * `SET market:idempotency:lock:<key> "1" NX EX <ttl>`.
 */
@Component
@ConditionalOnProperty(name = ["market.cache.redis-enabled"], havingValue = "true")
class RedisIdempotencyKeyStore(
    private val redis: StringRedisTemplate,
    @Value("\${market.idempotency.ttl-hours:24}") private val ttlHours: Long,
) : IdempotencyKeyStore {

    override fun acquireOrThrow(key: String) {
        val acquired = redis.opsForValue()
            .setIfAbsent(PREFIX + key, "1", Duration.ofHours(ttlHours))
        if (acquired != true) {
            throw DuplicateRequestException(key)
        }
    }

    /** 점유 해제 — 트랜잭션 rollback 시 호출. DEL 은 키가 없어도 0 반환 (멱등). */
    override fun release(key: String) {
        redis.delete(PREFIX + key)
    }

    companion object {
        private const val PREFIX = "market:idempotency:lock:"
    }
}
