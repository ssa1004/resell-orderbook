package com.example.market.adapter.out.persistence.jpa.repository;

import com.example.market.adapter.out.persistence.jpa.entity.TradeJpaEntity;
import com.example.market.domain.trading.TradeStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringDataTradeRepository extends JpaRepository<TradeJpaEntity, UUID> {

    @Query("""
            SELECT t FROM TradeJpaEntity t
            WHERE t.status = com.example.market.domain.trading.TradeStatus.CREATED
              AND t.createdAt < :cutoff
            ORDER BY t.createdAt ASC
            """)
    List<TradeJpaEntity> findStaleCreated(@Param("cutoff") Instant cutoff, Pageable pageable);

    List<TradeJpaEntity> findByStatus(TradeStatus status, Pageable pageable);

    /**
     * 첫 페이지 (cursor 없음) — 한 사용자가 buyer or seller 인 거래를 최신 순.
     * (created_at, id) DESC 복합 정렬로 *strict* 순서 보장 → cursor 가 결정성 있게 점프 가능.
     */
    @Query("""
            SELECT t FROM TradeJpaEntity t
            WHERE (t.sellerId = :userId OR t.buyerId = :userId)
            ORDER BY t.createdAt DESC, t.id DESC
            """)
    List<TradeJpaEntity> findByUserFirstPage(@Param("userId") String userId, Pageable pageable);

    /**
     * 다음 페이지 — (created_at, id) 가 (afterTime, afterId) 보다 *작은* row 들 (DESC 기준 = 더
     * 과거). 핵심은 *복합 비교* — 같은 ms 안의 두 row 사이는 id 로 결정.
     *
     * <p>JPA 가 row constructor 비교 ({@code (a, b) < (?, ?)}) 를 직접 표현하기 모호해
     * AND 풀어서 작성:</p>
     *
     * <pre>
     *   created_at &lt; afterTime
     *      OR (created_at = afterTime AND id &lt; afterId)
     * </pre>
     */
    @Query("""
            SELECT t FROM TradeJpaEntity t
            WHERE (t.sellerId = :userId OR t.buyerId = :userId)
              AND (t.createdAt < :afterTime
                   OR (t.createdAt = :afterTime AND t.id < :afterId))
            ORDER BY t.createdAt DESC, t.id DESC
            """)
    List<TradeJpaEntity> findByUserAfter(@Param("userId") String userId,
                                         @Param("afterTime") Instant afterTime,
                                         @Param("afterId") UUID afterId,
                                         Pageable pageable);
}
