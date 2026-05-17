package com.example.market.adapter.out.persistence.jpa

import com.example.market.adapter.out.persistence.jpa.mapper.OhlcCandleJpaMapper
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataOhlcCandleRepository
import com.example.market.application.port.out.OhlcCandleRepository
import com.example.market.domain.catalog.SkuId
import com.example.market.domain.marketdata.OhlcCandle
import com.example.market.domain.marketdata.OhlcPeriod
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class JpaOhlcCandleRepositoryAdapter(
    private val jpa: SpringDataOhlcCandleRepository,
) : OhlcCandleRepository {

    override fun save(candle: OhlcCandle) {
        jpa.save(OhlcCandleJpaMapper.toEntity(candle))
    }

    override fun findBySkuInRange(
        skuId: SkuId,
        period: OhlcPeriod,
        from: Instant,
        to: Instant,
        limit: Int,
    ): List<OhlcCandle> =
        jpa.findBySkuIdAndPeriodAndBucketStartBetweenOrderByBucketStartDesc(
            skuId.value, period, from, to, PageRequest.of(0, limit),
        ).map(OhlcCandleJpaMapper::toDomain)
}
