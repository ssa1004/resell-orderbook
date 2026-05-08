package com.example.market.adapter.out.persistence.idempotency;

import com.example.market.application.port.out.IdempotencyKeyStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis SETNX 기반 멱등성 키. 분산 환경 prod.
 *
 * <p>{@code SET market:idempotency:lock:<key> "1" NX EX <ttl>}.</p>
 */
@Component
@ConditionalOnProperty(name = "market.cache.redis-enabled", havingValue = "true")
@RequiredArgsConstructor
public class RedisIdempotencyKeyStore implements IdempotencyKeyStore {

    private final StringRedisTemplate redis;

    @Value("${market.idempotency.ttl-hours:24}")
    private long ttlHours;

    private static final String PREFIX = "market:idempotency:lock:";

    @Override
    public void acquireOrThrow(String key) {
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(PREFIX + key, "1", Duration.ofHours(ttlHours));
        if (!Boolean.TRUE.equals(acquired)) {
            throw new DuplicateRequestException(key);
        }
    }

    /** 점유 해제 — 트랜잭션 rollback 시 호출. DEL 은 키가 없어도 0 반환 (멱등). */
    @Override
    public void release(String key) {
        redis.delete(PREFIX + key);
    }
}
