package com.example.market.adapter.out.ratelimit;

import com.example.market.application.port.out.TokenBucketRateLimiter.Decision;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In-memory token bucket 의 동작 검증 — Lua 구현과 같은 알고리즘이라 정확성 검증은 여기서 충분.
 */
class InMemoryTokenBucketRateLimiterTest {

    private static final Duration ONE_SEC = Duration.ofSeconds(1);

    @Test
    void newBucket_startsFull_consumesUntilEmpty() {
        AdvancingClock clock = new AdvancingClock(Instant.parse("2026-05-08T12:00:00Z"));
        InMemoryTokenBucketRateLimiter limiter = new InMemoryTokenBucketRateLimiter(clock);

        // capacity=5, refill=1/s — 처음 5번은 전부 통과.
        for (int i = 0; i < 5; i++) {
            Decision d = limiter.tryConsume("user1:place", 5, 1, ONE_SEC);
            assertThat(d.allowed()).isTrue();
            assertThat(d.remaining()).isEqualTo(4 - i);
        }
        // 6번째는 거부.
        Decision blocked = limiter.tryConsume("user1:place", 5, 1, ONE_SEC);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.retryAfter()).isLessThanOrEqualTo(ONE_SEC);
    }

    @Test
    void refillReplenishesExactlyByElapsed() {
        AdvancingClock clock = new AdvancingClock(Instant.parse("2026-05-08T12:00:00Z"));
        InMemoryTokenBucketRateLimiter limiter = new InMemoryTokenBucketRateLimiter(clock);

        // 통 모두 소진
        for (int i = 0; i < 5; i++) {
            limiter.tryConsume("k", 5, 1, ONE_SEC);
        }
        assertThat(limiter.tryConsume("k", 5, 1, ONE_SEC).allowed()).isFalse();

        // 1초 진행 → 1개 채워짐
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryConsume("k", 5, 1, ONE_SEC).allowed()).isTrue();
        // 또 즉시 호출은 거부 (방금 채운 1개를 썼으므로 다시 0)
        assertThat(limiter.tryConsume("k", 5, 1, ONE_SEC).allowed()).isFalse();

        // 3초 더 진행 → 3개 채워짐, capacity 5 까지만
        clock.advance(Duration.ofSeconds(3));
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryConsume("k", 5, 1, ONE_SEC).allowed()).isTrue();
        }
        assertThat(limiter.tryConsume("k", 5, 1, ONE_SEC).allowed()).isFalse();
    }

    @Test
    void capacityCap_neverExceeded() {
        AdvancingClock clock = new AdvancingClock(Instant.parse("2026-05-08T12:00:00Z"));
        InMemoryTokenBucketRateLimiter limiter = new InMemoryTokenBucketRateLimiter(clock);

        // 첫 호출 후 1시간 방치 — 통이 가득해야 (capacity 5 초과 안 함)
        limiter.tryConsume("k", 5, 1, ONE_SEC);   // 4개 남음
        clock.advance(Duration.ofHours(1));

        // 5번 모두 통과해야 함 — 그 이상은 안 됨 (capacity 5 cap)
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume("k", 5, 1, ONE_SEC).allowed()).isTrue();
        }
        assertThat(limiter.tryConsume("k", 5, 1, ONE_SEC).allowed()).isFalse();
    }

    @Test
    void differentKeysHaveIndependentBuckets() {
        AdvancingClock clock = new AdvancingClock(Instant.parse("2026-05-08T12:00:00Z"));
        InMemoryTokenBucketRateLimiter limiter = new InMemoryTokenBucketRateLimiter(clock);

        for (int i = 0; i < 3; i++) {
            limiter.tryConsume("u1:p", 3, 1, ONE_SEC);
        }
        // u1 통은 비었지만 u2 통은 만땅.
        assertThat(limiter.tryConsume("u1:p", 3, 1, ONE_SEC).allowed()).isFalse();
        assertThat(limiter.tryConsume("u2:p", 3, 1, ONE_SEC).allowed()).isTrue();
    }

    @Test
    void retryAfter_isReasonable() {
        AdvancingClock clock = new AdvancingClock(Instant.parse("2026-05-08T12:00:00Z"));
        InMemoryTokenBucketRateLimiter limiter = new InMemoryTokenBucketRateLimiter(clock);

        // capacity=2, refill=2/sec → 2개 다 쓰면 다음 토큰까지 ~500ms 대기.
        limiter.tryConsume("k", 2, 2, ONE_SEC);
        limiter.tryConsume("k", 2, 2, ONE_SEC);
        Decision d = limiter.tryConsume("k", 2, 2, ONE_SEC);

        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfter().toMillis()).isBetween(0L, 600L);
    }

    static final class AdvancingClock extends Clock {
        private Instant now;
        AdvancingClock(Instant start) { this.now = start; }
        void advance(Duration d) { this.now = this.now.plus(d); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        @Override public long millis() { return now.toEpochMilli(); }
    }
}
