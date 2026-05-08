package com.example.market.adapter.out.persistence.jpa.repository;

import com.example.market.adapter.out.persistence.jpa.entity.PriceTickJpaEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataPriceTickRepository extends JpaRepository<PriceTickJpaEntity, Long> {

    /** 차트 / 최근 체결가 조회 — 시간 역순 (최근부터). */
    List<PriceTickJpaEntity> findBySkuIdAndOccurredAtBetweenOrderByOccurredAtDesc(
            UUID skuId, Instant from, Instant to, Pageable pageable);

    /** 가장 최근 체결 1건 — last trade 표시용. Snowflake id DESC = 시간 DESC (ADR-0018). */
    Optional<PriceTickJpaEntity> findFirstBySkuIdOrderByIdDesc(UUID skuId);

    /**
     * Cursor pagination — id &gt; afterId 인 tick 을 id ASC 로. snowflake id 가 시간 순이므로
     * 별도 occurred_at 정렬 / OFFSET 없이 다음 페이지를 잡는다.
     */
    List<PriceTickJpaEntity> findBySkuIdAndIdGreaterThanOrderByIdAsc(
            UUID skuId, Long afterId, Pageable pageable);

    /**
     * 24h 통계 — DB aggregation 으로 한번에 계산. tick 수가 많아도 효율적.
     * 통화 mix 가 없다고 가정 (단일 SKU = 단일 통화).
     */
    @Query("""
            SELECT new com.example.market.adapter.out.persistence.jpa.repository.SkuPriceAggregation(
                COUNT(p),
                MIN(p.priceAmount),
                AVG(p.priceAmount),
                MAX(p.priceAmount)
            )
              FROM PriceTickJpaEntity p
             WHERE p.skuId = :skuId
               AND p.occurredAt >= :from
               AND p.occurredAt <  :to
            """)
    SkuPriceAggregation aggregate(@Param("skuId") UUID skuId,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to);

    /** {@link #aggregate} 의 응답 DTO. count=0 이면 다른 필드는 null. */
    record SkuPriceAggregation(long count, BigDecimal min, Double avg, BigDecimal max) {}

    /** OHLC aggregation 배치 — 이 시간 구간에 1건이라도 거래가 있던 SKU 만. */
    @Query("""
            SELECT DISTINCT p.skuId FROM PriceTickJpaEntity p
             WHERE p.occurredAt >= :from
               AND p.occurredAt <  :to
            """)
    List<UUID> findDistinctSkuIdsInRange(@Param("from") Instant from,
                                         @Param("to") Instant to);
}
