package com.example.market.application.port.out;

import com.example.market.domain.trading.Trade;
import com.example.market.domain.trading.TradeId;
import com.example.market.domain.trading.TradeStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TradeRepository {
    void save(Trade trade);
    Optional<Trade> findById(TradeId id);

    /** TTL 만료된 CREATED 거래 (Spring Batch 용). */
    List<Trade> findStaleCreated(Instant cutoff);

    List<Trade> findByStatus(TradeStatus status, int limit);

    /**
     * 한 사용자 (구매자 or 판매자) 의 거래 내역을 *최신 → 과거* 순으로 cursor pagination (ADR-0025).
     *
     * <p>DB query 패턴 (역순):</p>
     * <pre>
     *   WHERE (seller_id = ? OR buyer_id = ?)
     *     AND (created_at, id) &lt; (?, ?)   -- afterTime / afterId 가 null 이 아닐 때만
     *   ORDER BY created_at DESC, id DESC
     *   LIMIT ?
     * </pre>
     *
     * <p>{@code afterTime} / {@code afterId} 가 null 이면 첫 페이지부터.</p>
     *
     * @param userId    조회 대상 사용자
     * @param afterTime 직전 페이지 마지막 row 의 created_at (null = 첫 페이지)
     * @param afterId   직전 페이지 마지막 row 의 id        (null = 첫 페이지)
     * @param limit     가져올 최대 row 수 (caller 는 보통 N+1 패턴으로 1 더 요청)
     */
    List<Trade> findByUserCursor(String userId, Instant afterTime, UUID afterId, int limit);
}
