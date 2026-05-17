package com.example.market.adapter.out.persistence.jpa.mapper

import com.example.market.adapter.out.persistence.jpa.entity.PriceTickJpaEntity
import com.example.market.domain.catalog.SkuId
import com.example.market.domain.marketdata.PriceTick
import com.example.market.domain.shared.Money
import com.example.market.domain.trading.TradeId
import java.util.Currency

object PriceTickJpaMapper {

    @JvmStatic
    fun toEntity(t: PriceTick): PriceTickJpaEntity = PriceTickJpaEntity(
        id = t.id,
        tradeId = t.tradeId.value,
        skuId = t.skuId.value,
        priceAmount = t.price.amount,
        currency = t.price.currency.currencyCode,
        occurredAt = t.occurredAt,
    )

    @JvmStatic
    fun toDomain(e: PriceTickJpaEntity): PriceTick = PriceTick(
        e.id!!,
        TradeId(e.tradeId!!),
        SkuId(e.skuId!!),
        Money.of(e.priceAmount!!, Currency.getInstance(e.currency!!)),
        e.occurredAt!!,
    )
}
