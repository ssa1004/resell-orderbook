package com.example.market.adapter.out.persistence.jpa.repository

import com.example.market.adapter.out.persistence.jpa.entity.PriceTickJpaEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.Optional
import java.util.UUID

interface SpringDataPriceTickRepository : JpaRepository<PriceTickJpaEntity, Long> {

    /** 차트 / 최근 체결가 조회 — 시간 역순 (최근부터). */
    fun findBySkuIdAndOccurredAtBetweenOrderByOccurredAtDesc(
        skuId: UUID,
        from: Instant,
        to: Instant,
        pageable: Pageable,
    ): List<PriceTickJpaEntity>

    /** 가장 최근 체결 1건 — last trade 표시용. Snowflake id DESC = 시간 DESC (ADR-0018). */
    fun findFirstBySkuIdOrderByIdDesc(skuId: UUID): Optional<PriceTickJpaEntity>

    /**
     * Cursor pagination — id > afterId 인 tick 을 id ASC 로. snowflake id 가 시간 순이므로
     * 별도 occurred_at 정렬 / OFFSET 없이 다음 페이지를 잡는다.
     */
    fun findBySkuIdAndIdGreaterThanOrderByIdAsc(
        skuId: UUID,
        afterId: Long,
        pageable: Pageable,
    ): List<PriceTickJpaEntity>

    /**
     * 24h 통계 — DB aggregation 으로 한번에 계산. tick 수가 많아도 효율적.
     * 통화 mix 가 없다고 가정 (단일 SKU = 단일 통화).
     */
    @Query(
        """
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
        """,
    )
    fun aggregate(
        @Param("skuId") skuId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): SkuPriceAggregation

    /** OHLC aggregation 배치 — 이 시간 구간에 1건이라도 거래가 있던 SKU 만. */
    @Query(
        """
        SELECT DISTINCT p.skuId FROM PriceTickJpaEntity p
         WHERE p.occurredAt >= :from
           AND p.occurredAt <  :to
        """,
    )
    fun findDistinctSkuIdsInRange(
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<UUID>
}
