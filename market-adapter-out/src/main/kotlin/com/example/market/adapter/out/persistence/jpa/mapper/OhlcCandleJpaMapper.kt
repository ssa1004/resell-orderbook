package com.example.market.adapter.out.persistence.jpa.mapper

import com.example.market.adapter.out.persistence.jpa.entity.OhlcCandleJpaEntity
import com.example.market.domain.catalog.SkuId
import com.example.market.domain.marketdata.OhlcCandle
import com.example.market.domain.shared.Money
import java.util.Currency

object OhlcCandleJpaMapper {

    @JvmStatic
    fun toEntity(c: OhlcCandle): OhlcCandleJpaEntity = OhlcCandleJpaEntity(
        id = c.id,
        skuId = c.skuId.value,
        period = c.period,
        bucketStart = c.bucketStart,
        openAmount = c.open.amount,
        highAmount = c.high.amount,
        lowAmount = c.low.amount,
        closeAmount = c.close.amount,
        currency = c.open.currency.currencyCode,
        volume = c.volume,
        tradeCount = c.tradeCount,
    )

    @JvmStatic
    fun toDomain(e: OhlcCandleJpaEntity): OhlcCandle {
        val currency = Currency.getInstance(e.currency!!)
        return OhlcCandle(
            e.id!!,
            SkuId(e.skuId!!),
            e.period!!,
            e.bucketStart!!,
            Money.of(e.openAmount!!, currency),
            Money.of(e.highAmount!!, currency),
            Money.of(e.lowAmount!!, currency),
            Money.of(e.closeAmount!!, currency),
            e.volume,
            e.tradeCount,
        )
    }
}
