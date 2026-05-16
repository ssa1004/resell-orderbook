package com.example.market.application.port.out

import com.example.market.domain.trading.Trade
import com.example.market.domain.trading.TradeId
import com.example.market.domain.trading.TradeStatus
import java.time.Instant
import java.util.Optional
import java.util.UUID

interface TradeRepository {
    fun save(trade: Trade)
    fun findById(id: TradeId): Optional<Trade>

    /**
     * TTL 만료된 CREATED 거래 (Spring Batch 용).
     *
     * `limit` 은 한 batch 의 처리 hint — caller (AutoCancel job 등) 가 한 트랜잭션에서
     * 처리하려는 수와 일치시켜야 fetch / lock 비용이 의도와 어긋나지 않는다. 이전엔 200 으로
     * hard-coded 되어 있어 batch job 이 500 / 1000 으로 호출해도 200 만 처리되는 문제가 있었다.
     */
    fun findStaleCreated(cutoff: Instant, limit: Int): List<Trade>

    fun findByStatus(status: TradeStatus, limit: Int): List<Trade>

    /**
     * 한 사용자 (구매자 or 판매자) 의 거래 내역을 *최신 → 과거* 순으로 cursor pagination (ADR-0025).
     *
     * DB query 패턴 (역순):
     * ```
     *   WHERE (seller_id = ? OR buyer_id = ?)
     *     AND (created_at, id) < (?, ?)   -- afterTime / afterId 가 null 이 아닐 때만
     *   ORDER BY created_at DESC, id DESC
     *   LIMIT ?
     * ```
     *
     * `afterTime` / `afterId` 가 null 이면 첫 페이지부터.
     *
     * @param userId    조회 대상 사용자
     * @param afterTime 직전 페이지 마지막 row 의 created_at (null = 첫 페이지)
     * @param afterId   직전 페이지 마지막 row 의 id        (null = 첫 페이지)
     * @param limit     가져올 최대 row 수 (caller 는 보통 N+1 패턴으로 1 더 요청)
     */
    fun findByUserCursor(userId: String, afterTime: Instant?, afterId: UUID?, limit: Int): List<Trade>
}
