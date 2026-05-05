package com.example.market.application.port.out;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.trading.Bid;
import com.example.market.domain.trading.Listing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 호가창 query — 매칭에 필요한 best 호가를 *원자적으로* 조회 + (옵션) advisory lock.
 *
 * <p>구현 (JPA adapter):</p>
 * <ul>
 *   <li>{@link #acquireSkuLock} — {@code SELECT pg_advisory_xact_lock(hash(sku_id))} —
 *       같은 SKU 의 매칭이 직렬화. 트랜잭션 끝까지 자동 해제. deadlock 결정적 회피.</li>
 *   <li>{@link #findHighestBidForUpdate} / {@link #findLowestAskForUpdate} —
 *       {@code SELECT ... ORDER BY ... LIMIT 1 FOR UPDATE SKIP LOCKED}.
 *       만료 호가는 query 단계에서 거름 (expires_at &gt; now AND status='ACTIVE').</li>
 * </ul>
 *
 * <p>read-only 조회 ({@link #topNAsks}/{@link #topNBids}) 는 락 없음 — 호가창 표시용.</p>
 */
public interface OrderBookQueryPort {

    /**
     * SKU 단위 advisory lock. 트랜잭션 안에서 반드시 best 호가 조회 *전에* 호출해서
     * 같은 SKU 동시 매칭을 직렬화. PostgreSQL pg_advisory_xact_lock 사용.
     */
    void acquireSkuLock(SkuId skuId);

    /** Highest BID — 가격 ↓, 시간 ↑. status=ACTIVE AND expires_at > now AND not matched. */
    Optional<Bid> findHighestBidForUpdate(SkuId skuId, Instant now);

    /** Lowest ASK — 가격 ↑, 시간 ↑. */
    Optional<Listing> findLowestAskForUpdate(SkuId skuId, Instant now);

    /** read-only — 호가창 view (락 없음). */
    List<Listing> topNAsks(SkuId skuId, int limit, Instant now);
    List<Bid> topNBids(SkuId skuId, int limit, Instant now);
}
