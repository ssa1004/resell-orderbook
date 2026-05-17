package com.example.market.adapter.out.persistence.jpa.mapper

import com.example.market.adapter.out.persistence.jpa.entity.PayoutJpaEntity
import com.example.market.domain.settlement.Payout
import com.example.market.domain.settlement.PayoutId
import com.example.market.domain.shared.Money
import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.TradeId
import java.util.Currency

object PayoutJpaMapper {

    @JvmStatic
    fun toEntity(p: Payout): PayoutJpaEntity {
        val e = PayoutJpaEntity()
        e.id = p.id.value
        e.tradeId = p.tradeId.value
        e.sellerId = p.sellerId.value
        e.tradeAmount = p.tradeAmount.amount
        e.sellerCommission = p.sellerCommission.amount
        e.processingFee = p.processingFee.amount
        e.netAmount = p.netAmount.amount
        e.currencyCode = p.tradeAmount.currency.currencyCode
        e.status = p.status
        e.bankTransferId = p.bankTransferId
        e.createdAt = p.createdAt
        e.completedAt = p.completedAt
        e.version = p.version
        return e
    }

    @JvmStatic
    fun toDomain(e: PayoutJpaEntity): Payout {
        val c = Currency.getInstance(e.currencyCode!!)
        return Payout.restore(
            PayoutId(e.id!!),
            TradeId(e.tradeId!!),
            UserId.of(e.sellerId!!),
            Money.of(e.tradeAmount!!, c),
            Money.of(e.sellerCommission!!, c),
            Money.of(e.processingFee!!, c),
            Money.of(e.netAmount!!, c),
            e.status!!,
            e.bankTransferId,
            e.createdAt!!,
            e.completedAt,
            e.version,
        )
    }
}
