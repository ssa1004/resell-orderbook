package com.example.market.adapter.out.persistence.jpa.mapper

import com.example.market.adapter.out.persistence.jpa.entity.ListingJpaEntity
import com.example.market.domain.catalog.SkuId
import com.example.market.domain.shared.Money
import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.Listing
import com.example.market.domain.trading.ListingId
import com.example.market.domain.trading.TradeId
import java.util.Currency

object ListingJpaMapper {

    @JvmStatic
    fun toEntity(l: Listing): ListingJpaEntity {
        val e = ListingJpaEntity()
        e.id = l.id.value
        e.skuId = l.skuId.value
        e.sellerId = l.sellerId.value
        e.askPrice = l.askPrice.amount
        e.currencyCode = l.askPrice.currency.currencyCode
        e.status = l.status
        e.matchedTradeId = l.matchedTradeId?.value
        e.expiresAt = l.expiresAt
        e.createdAt = l.createdAt
        e.version = l.version
        return e
    }

    @JvmStatic
    fun toDomain(e: ListingJpaEntity): Listing {
        val price = Money.of(e.askPrice!!, Currency.getInstance(e.currencyCode!!))
        val matched = e.matchedTradeId?.let { TradeId(it) }
        return Listing.restore(
            ListingId(e.id!!),
            SkuId(e.skuId!!),
            UserId.of(e.sellerId!!),
            price,
            e.expiresAt!!,
            e.createdAt!!,
            e.status!!,
            matched,
            e.version,
        )
    }
}
