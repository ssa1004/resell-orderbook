package com.example.market.adapter.out.ratelimit

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * In-memory admin rate limiter — 정책별 capacity / scope 격리 / refill 확인.
 */
class InMemoryAdminRateLimiterTest {

    @Test
    fun `bulk scope rejects after 10 calls within a minute`() {
        val clock = MutableClock(Instant.parse("2026-05-15T10:00:00Z"))
        val limiter = InMemoryAdminRateLimiter(clock)

        // 1~10 번 모두 허용 — bulk capacity 10.
        repeat(10) {
            assertTrue(limiter.tryAcquire("dlq.bulk", "ops-alice").allowed, "call ${it + 1}")
        }
        // 11번째 — 거부 + retryAfter > 0.
        val rejected = limiter.tryAcquire("dlq.bulk", "ops-alice")
        assertFalse(rejected.allowed)
        assertTrue(rejected.retryAfter.toMillis() > 0)
    }

    @Test
    fun `different actors do not share the bucket`() {
        val clock = MutableClock(Instant.parse("2026-05-15T10:00:00Z"))
        val limiter = InMemoryAdminRateLimiter(clock)

        repeat(10) { limiter.tryAcquire("dlq.bulk", "ops-alice") }
        // alice 는 10회 소진. bob 은 아직 가득.
        assertTrue(limiter.tryAcquire("dlq.bulk", "ops-bob").allowed)
    }

    @Test
    fun `read scope has higher capacity than write scope`() {
        val clock = MutableClock(Instant.parse("2026-05-15T10:00:00Z"))
        val limiter = InMemoryAdminRateLimiter(clock)

        // write 는 60회까지만, read 는 120회까지.
        repeat(60) { assertTrue(limiter.tryAcquire("dlq.write", "ops-alice").allowed) }
        assertFalse(limiter.tryAcquire("dlq.write", "ops-alice").allowed)

        repeat(120) { assertTrue(limiter.tryAcquire("dlq.read", "ops-alice").allowed) }
        assertFalse(limiter.tryAcquire("dlq.read", "ops-alice").allowed)
    }

    @Test
    fun `refill restores tokens after the window`() {
        val clock = MutableClock(Instant.parse("2026-05-15T10:00:00Z"))
        val limiter = InMemoryAdminRateLimiter(clock)

        repeat(10) { limiter.tryAcquire("dlq.bulk", "ops-alice") }
        assertFalse(limiter.tryAcquire("dlq.bulk", "ops-alice").allowed)

        // 1분 후 — 10 토큰 전체 refill.
        clock.advance(Duration.ofMinutes(1))
        assertTrue(limiter.tryAcquire("dlq.bulk", "ops-alice").allowed)
    }

    /** 테스트용 mutable clock — Clock.systemUTC 가 아니라 시간 흐름을 명시 제어. */
    private class MutableClock(initial: Instant) : Clock() {
        @Volatile private var current: Instant = initial
        fun advance(d: Duration) { current = current.plus(d) }
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = current
        override fun millis() = current.toEpochMilli()
    }
}
