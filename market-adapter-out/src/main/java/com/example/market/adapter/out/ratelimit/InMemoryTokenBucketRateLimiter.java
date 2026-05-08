package com.example.market.adapter.out.ratelimit;

import com.example.market.application.port.out.TokenBucketRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 단일 인스턴스용 in-memory token bucket — dev / 단위 테스트.
 *
 * <p>분산 환경에서는 인스턴스끼리 카운터가 안 맞으므로 {@link RedisTokenBucketRateLimiter} 사용.
 * dev 는 인스턴스 1대라 정확.</p>
 *
 * <p>스레드 안전성은 {@link Bucket#tryConsume} 의 {@code synchronized} 로 확보. bucket 별 락이라
 * 다른 키 사이의 경합 없음. {@link ConcurrentHashMap#computeIfAbsent} 가 bucket 생성 자체를
 * 직렬화.</p>
 */
@Component
@ConditionalOnProperty(name = "market.cache.redis-enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryTokenBucketRateLimiter implements TokenBucketRateLimiter {

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryTokenBucketRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Decision tryConsume(String key, int capacity, int refillTokens, Duration refillInterval) {
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, clock.millis()));
        return b.tryConsume(capacity, refillTokens, refillInterval.toMillis(), clock.millis());
    }

    static final class Bucket {
        private long tokens;
        private long lastRefillMs;

        Bucket(long initialTokens, long nowMs) {
            this.tokens = initialTokens;
            this.lastRefillMs = nowMs;
        }

        synchronized Decision tryConsume(int capacity, int refillTokens, long refillIvMs, long nowMs) {
            long elapsed = Math.max(0, nowMs - lastRefillMs);
            long refilled = (elapsed * refillTokens) / refillIvMs;
            if (refilled > 0) {
                tokens = Math.min(capacity, tokens + refilled);
                // 정수 토큰 만큼만 시간 진행 (Lua 스크립트와 동일 — 소수 토큰 누락 방지).
                lastRefillMs += (refilled * refillIvMs) / refillTokens;
            }
            if (tokens > 0) {
                tokens--;
                return Decision.allowed((int) tokens);
            }
            long msPerToken = (refillIvMs + refillTokens - 1) / refillTokens;   // ceil
            long progress = nowMs - lastRefillMs;
            long retryMs = Math.max(0, msPerToken - progress);
            return Decision.rejected(Duration.ofMillis(retryMs));
        }
    }
}
