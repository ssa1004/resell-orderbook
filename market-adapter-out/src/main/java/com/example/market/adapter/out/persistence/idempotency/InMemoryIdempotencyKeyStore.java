package com.example.market.adapter.out.persistence.idempotency;

import com.example.market.application.port.out.IdempotencyKeyStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 단일 인스턴스용 in-memory IdempotencyKeyStore. dev 기본값.
 *
 * <p>분산 환경에서는 RedisIdempotencyKeyStore 사용 (market.cache.redis-enabled=true).</p>
 */
@Component
@ConditionalOnProperty(name = "market.cache.redis-enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryIdempotencyKeyStore implements IdempotencyKeyStore {

    private final ConcurrentMap<String, Boolean> seen = new ConcurrentHashMap<>();

    @Override
    public void acquireOrThrow(String key) {
        if (seen.putIfAbsent(key, Boolean.TRUE) != null) {
            throw new DuplicateRequestException(key);
        }
    }
}
