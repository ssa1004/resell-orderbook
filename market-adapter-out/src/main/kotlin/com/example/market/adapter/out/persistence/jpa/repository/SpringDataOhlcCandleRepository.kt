package com.example.market.adapter.out.persistence.jpa.repository

import com.example.market.adapter.out.persistence.jpa.entity.OhlcCandleJpaEntity
import com.example.market.domain.marketdata.OhlcPeriod
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.Optional
import java.util.UUID

interface SpringDataOhlcCandleRepository : JpaRepository<OhlcCandleJpaEntity, UUID> {

    /** 차트 query — 시간 역순 (최근부터). */
    fun findBySkuIdAndPeriodAndBucketStartBetweenOrderByBucketStartDesc(
        skuId: UUID,
        period: OhlcPeriod,
        from: Instant,
        to: Instant,
        pageable: Pageable,
    ): List<OhlcCandleJpaEntity>

    /** 가장 최근 candle — 다음 batch 가 어디부터 집계할지 결정용. */
    fun findFirstBySkuIdAndPeriodOrderByBucketStartDesc(
        skuId: UUID,
        period: OhlcPeriod,
    ): Optional<OhlcCandleJpaEntity>
}
