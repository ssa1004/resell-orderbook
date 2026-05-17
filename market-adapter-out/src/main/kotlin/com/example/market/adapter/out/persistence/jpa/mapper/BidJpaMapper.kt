package com.example.market.adapter.out.persistence.jpa.mapper

import com.example.market.adapter.out.persistence.jpa.entity.BidJpaEntity
import com.example.market.domain.catalog.SkuId
import com.example.market.domain.shared.Money
import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.Bid
import com.example.market.domain.trading.BidId
import com.example.market.domain.trading.TradeId
import java.util.Currency

object BidJpaMapper {

    @JvmStatic
    fun toEntity(b: Bid): BidJpaEntity {
        val e = BidJpaEntity()
        e.id = b.id.value
        e.skuId = b.skuId.value
        e.buyerId = b.buyerId.value
        e.bidPrice = b.bidPrice.amount
        e.currencyCode = b.bidPrice.currency.currencyCode
        e.status = b.status
        e.matchedTradeId = b.matchedTradeId?.value
        e.expiresAt = b.expiresAt
        e.createdAt = b.createdAt
        e.version = b.version
        return e
    }

    @JvmStatic
    fun toDomain(e: BidJpaEntity): Bid {
        val price = Money.of(e.bidPrice!!, Currency.getInstance(e.currencyCode!!))
        val matched = e.matchedTradeId?.let { TradeId(it) }
        return Bid.restore(
            BidId(e.id!!),
            SkuId(e.skuId!!),
            UserId.of(e.buyerId!!),
            price,
            e.expiresAt!!,
            e.createdAt!!,
            e.status!!,
            matched,
            e.version,
        )
    }
}
