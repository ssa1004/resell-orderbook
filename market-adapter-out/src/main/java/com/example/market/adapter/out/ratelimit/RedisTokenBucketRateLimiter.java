package com.example.market.adapter.out.ratelimit;

import com.example.market.application.port.out.TokenBucketRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Lua 스크립트 기반 atomic token bucket (ADR-0020).
 *
 * <p>Redis 의 {@code EVAL} 한 번에 read → refill → try-consume → write 전부 — 동시에 도착한
 * 두 요청이 같은 토큰을 두 번 빼는 race 없음.</p>
 *
 * <p>Redis 가 죽으면 {@link #tryConsume} 는 throw 하지 않고 *fail-open* (= allowed) 로 fallback —
 * 가용성 우선. 호출 측은 일반 응답 흐름 유지. 보안 민감 endpoint 는 별도 정책 필요 (ADR-0020).</p>
 */
@Component
@ConditionalOnProperty(name = "market.cache.redis-enabled", havingValue = "true")
@Slf4j
public class RedisTokenBucketRateLimiter implements TokenBucketRateLimiter {

    /** 모든 bucket 키 prefix — 운영에서 다른 도메인 키와 섞이지 않게. */
    private static final String KEY_PREFIX = "rl:tb:";

    private final StringRedisTemplate redis;
    private final Clock clock;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
        @SuppressWarnings("rawtypes")
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("scripts/ratelimit/token_bucket.lua")));
        s.setResultType(List.class);
        this.script = s;
    }

    @Override
    public Decision tryConsume(String key, int capacity, int refillTokens, Duration refillInterval) {
        if (capacity <= 0 || refillTokens <= 0 || refillInterval.isZero() || refillInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "capacity / refillTokens / refillInterval must be positive — got "
                            + capacity + " / " + refillTokens + " / " + refillInterval);
        }
        long nowMs = clock.millis();
        try {
            @SuppressWarnings("unchecked")
            List<Long> result = (List<Long>) redis.execute(
                    script,
                    List.of(KEY_PREFIX + key),
                    String.valueOf(capacity),
                    String.valueOf(refillTokens),
                    String.valueOf(refillInterval.toMillis()),
                    String.valueOf(nowMs));
            long allowed = result.get(0);
            int remaining = result.get(1).intValue();
            long retryAfterMs = result.get(2);
            return allowed == 1
                    ? Decision.allowed(remaining)
                    : Decision.rejected(Duration.ofMillis(retryAfterMs));
        } catch (Exception e) {
            // fail-open: Redis 장애가 사용자 경험을 깨지 않도록.
            log.warn("rate limiter 실패 — fail-open 로 통과 key={} reason={}", key, e.getMessage());
            return Decision.allowed(capacity);
        }
    }
}
