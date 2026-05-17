package com.example.market.adapter.out.persistence.jpa.mapper

import com.example.market.adapter.out.persistence.jpa.entity.TradeJpaEntity
import com.example.market.domain.catalog.SkuId
import com.example.market.domain.settlement.FeeSnapshot
import com.example.market.domain.shared.Money
import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.BidId
import com.example.market.domain.trading.ListingId
import com.example.market.domain.trading.Trade
import com.example.market.domain.trading.TradeId
import java.util.Currency

object TradeJpaMapper {

    @JvmStatic
    fun toEntity(t: Trade): TradeJpaEntity {
        val e = TradeJpaEntity()
        e.id = t.id.value
        e.skuId = t.skuId.value
        e.listingId = t.listingId.value
        e.bidId = t.bidId.value
        e.sellerId = t.sellerId.value
        e.buyerId = t.buyerId.value
        e.price = t.price.amount
        e.currencyCode = t.price.currency.currencyCode

        val s = t.feeSnapshot
        e.feeSellerRate = s.sellerCommissionRate
        e.feeBuyerRate = s.buyerCommissionRate
        e.feeInspection = s.inspectionFee.amount
        e.feeShipping = s.shippingFee.amount
        e.feeProcessing = s.fixedProcessingFee.amount
        e.feeSellerCommission = s.sellerCommission.amount
        e.feeBuyerCommission = s.buyerCommission.amount
        e.buyerCharge = s.buyerCharge.amount
        e.sellerNet = s.sellerNet.amount

        e.status = t.status
        e.pgPaymentId = t.pgPaymentId
        e.inspectionFailReason = t.inspectionFailReason
        e.createdAt = t.createdAt
        e.updatedAt = t.updatedAt
        e.version = t.version
        return e
    }

    @JvmStatic
    fun toDomain(e: TradeJpaEntity): Trade {
        val currency = Currency.getInstance(e.currencyCode!!)
        val price = Money.of(e.price!!, currency)
        val snap = FeeSnapshot(
            price,
            e.feeSellerRate!!,
            e.feeBuyerRate!!,
            Money.of(e.feeInspection!!, currency),
            Money.of(e.feeShipping!!, currency),
            Money.of(e.feeProcessing!!, currency),
            Money.of(e.feeSellerCommission!!, currency),
            Money.of(e.feeBuyerCommission!!, currency),
            Money.of(e.buyerCharge!!, currency),
            Money.of(e.sellerNet!!, currency),
        )
        return Trade.restore(
            TradeId(e.id!!),
            SkuId(e.skuId!!),
            ListingId(e.listingId!!),
            BidId(e.bidId!!),
            UserId.of(e.sellerId!!),
            UserId.of(e.buyerId!!),
            price,
            snap,
            e.status!!,
            e.pgPaymentId,
            e.inspectionFailReason,
            e.createdAt!!,
            e.updatedAt!!,
            e.version,
        )
    }
}
