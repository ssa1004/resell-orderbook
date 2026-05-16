package com.example.market.application.port.out

import com.example.market.domain.catalog.SkuId
import com.example.market.domain.trading.Bid
import com.example.market.domain.trading.Listing
import java.time.Instant
import java.util.Optional

/**
 * 호가창 query — 매칭에 필요한 best 호가를 *원자적으로* 조회 + (옵션) advisory lock.
 *
 * 구현 (JPA adapter):
 * - [acquireSkuLock] — `SELECT pg_advisory_xact_lock(hash(sku_id))` —
 *   같은 SKU 의 매칭이 직렬화. 트랜잭션 끝까지 자동 해제. deadlock 결정적 회피.
 * - [findHighestBidForUpdate] / [findLowestAskForUpdate] —
 *   `SELECT ... ORDER BY ... LIMIT 1 FOR UPDATE SKIP LOCKED`.
 *   만료 호가는 query 단계에서 거름 (expires_at > now AND status='ACTIVE').
 *
 * read-only 조회 ([topNAsks]/[topNBids]) 는 락 없음 — 호가창 표시용.
 */
interface OrderBookQueryPort {

    /**
     * SKU 단위 advisory lock. 트랜잭션 안에서 best 호가 조회 *이전에* 호출해 같은 SKU 동시 매칭을
     * 직렬화한다. PostgreSQL `pg_advisory_xact_lock` 사용.
     */
    fun acquireSkuLock(skuId: SkuId)

    /** Highest BID — 가격 ↓, 시간 ↑. status=ACTIVE AND expires_at > now AND not matched. */
    fun findHighestBidForUpdate(skuId: SkuId, now: Instant): Optional<Bid>

    /** Lowest ASK — 가격 ↑, 시간 ↑. */
    fun findLowestAskForUpdate(skuId: SkuId, now: Instant): Optional<Listing>

    /** read-only — 호가창 view (락 없음). */
    fun topNAsks(skuId: SkuId, limit: Int, now: Instant): List<Listing>
    fun topNBids(skuId: SkuId, limit: Int, now: Instant): List<Bid>
}
