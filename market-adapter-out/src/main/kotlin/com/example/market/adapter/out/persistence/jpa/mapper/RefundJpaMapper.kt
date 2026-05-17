package com.example.market.adapter.out.persistence.jpa.mapper

import com.example.market.adapter.out.persistence.jpa.entity.RefundJpaEntity
import com.example.market.domain.settlement.Refund
import com.example.market.domain.settlement.RefundId
import com.example.market.domain.shared.Money
import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.TradeId
import java.util.Currency

object RefundJpaMapper {

    @JvmStatic
    fun toEntity(r: Refund): RefundJpaEntity {
        val e = RefundJpaEntity()
        e.id = r.id.value
        e.tradeId = r.tradeId.value
        e.buyerId = r.buyerId.value
        e.amount = r.amount.amount
        e.currencyCode = r.amount.currency.currencyCode
        e.reason = r.reason
        e.status = r.status
        e.pgRefundId = r.pgRefundId
        e.requestedAt = r.requestedAt
        e.completedAt = r.completedAt
        e.version = r.version
        return e
    }

    @JvmStatic
    fun toDomain(e: RefundJpaEntity): Refund {
        val c = Currency.getInstance(e.currencyCode!!)
        return Refund.restore(
            RefundId(e.id!!),
            TradeId(e.tradeId!!),
            UserId.of(e.buyerId!!),
            Money.of(e.amount!!, c),
            e.reason,
            e.status!!,
            e.pgRefundId,
            e.requestedAt!!,
            e.completedAt,
            e.version,
        )
    }
}
