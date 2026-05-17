package com.example.market.adapter.out.persistence.idempotency

import com.example.market.application.port.out.IdempotencyKeyStore
import com.example.market.application.port.out.IdempotencyKeyStore.DuplicateRequestException
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 단일 인스턴스용 in-memory IdempotencyKeyStore. dev 기본값.
 *
 * 분산 환경에서는 [RedisIdempotencyKeyStore] 사용 (market.cache.redis-enabled=true).
 *
 * TTL 처리: `market.idempotency.ttl-hours` 가 지난 키는 다음 호출 때 만료된 것으로 간주
 * (lazy expiry). 만료된 키와 마주치면 새 키처럼 받아들이고, 일정 호출 주기마다 stale 항목을 일괄
 * 정리해 메모리 누수를 막는다.
 */
@Component
@ConditionalOnProperty(
    name = ["market.cache.redis-enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class InMemoryIdempotencyKeyStore(
    private val clock: Clock,
    @Value("\${market.idempotency.ttl-hours:24}") ttlHours: Long,
) : IdempotencyKeyStore {

    private val seen: ConcurrentMap<String, Instant> = ConcurrentHashMap()
    private val callCount = AtomicLong()
    private val ttl: Duration = Duration.ofHours(ttlHours)

    override fun acquireOrThrow(key: String) {
        val now = clock.instant()
        val expiry = now.plus(ttl)
        // putIfAbsent + compute 조합으로 "신규 점유 vs 기존 점유" 판정을 하나의 원자 연산으로.
        // 동시 N개 스레드가 같은 expiry 값으로 들어와도 1개만 신규로 인정된다 (이전 merge 기반
        // 구현은 BiFunction 안에서 prev/candidate Instant 가 동일 ns 일 때 둘 다 신규로 판정되는
        // race 가 있었다).
        val acquired = BooleanArray(1)
        seen.compute(key) { _, existing ->
            if (existing == null || existing.isBefore(now)) {
                acquired[0] = true
                expiry
            } else {
                existing
            }
        }
        if (!acquired[0]) {
            throw DuplicateRequestException(key)
        }
        if (callCount.incrementAndGet() % SWEEP_INTERVAL == 0L) {
            sweepExpired(now)
        }
    }

    /** 점유 해제 — 트랜잭션 rollback 시 호출. 키가 없으면 no-op. */
    override fun release(key: String) {
        seen.remove(key)
    }

    private fun sweepExpired(now: Instant) {
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.isBefore(now)) {
                it.remove()
            }
        }
    }

    companion object {
        /** 누적 호출 수가 이 값을 넘을 때마다 한 번씩 stale 항목을 sweep. */
        private const val SWEEP_INTERVAL = 1024
    }
}
