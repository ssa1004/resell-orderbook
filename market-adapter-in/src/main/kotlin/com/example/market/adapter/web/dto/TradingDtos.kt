package com.example.market.adapter.web.dto

import com.example.market.application.command.BuyNowCommand
import com.example.market.application.command.PlaceBidCommand
import com.example.market.application.command.PlaceListingCommand
import com.example.market.application.command.SellNowCommand
import com.example.market.application.port.`in`.OrderBookQueryUseCase
import com.example.market.domain.catalog.SkuId
import com.example.market.domain.shared.Money
import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.Trade
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import kotlin.jvm.optionals.getOrNull

// ── Listing ───────────────────────────────

data class PlaceListingRequest(
    @field:NotBlank val skuId: String,
    @field:Positive @field:Max(10_000_000_000L) val price: Long,
    @field:NotBlank @field:Size(min = 3, max = 3) val currency: String = "KRW",
) {
    fun toCommand(idempotencyKey: String, sellerId: UserId) = PlaceListingCommand(
        idempotencyKey, sellerId, SkuId.of(skuId),
        Money.of(BigDecimal.valueOf(price), Currency.getInstance(currency)),
    )
}

data class PlaceListingResponse(
    val listingId: String,
    val matchedTradeId: String?,
)

// ── Bid ───────────────────────────────

data class PlaceBidRequest(
    @field:NotBlank val skuId: String,
    @field:Positive @field:Max(10_000_000_000L) val price: Long,
    @field:NotBlank @field:Size(min = 3, max = 3) val currency: String = "KRW",
) {
    fun toCommand(idempotencyKey: String, buyerId: UserId) = PlaceBidCommand(
        idempotencyKey, buyerId, SkuId.of(skuId),
        Money.of(BigDecimal.valueOf(price), Currency.getInstance(currency)),
    )
}

data class PlaceBidResponse(
    val bidId: String,
    val matchedTradeId: String?,
)

// ── BuyNow / SellNow ───────────────────────────────

data class InstantTradeRequest(
    @field:NotBlank val skuId: String,
)

fun InstantTradeRequest.toBuyNowCommand(idempotencyKey: String, buyerId: UserId) =
    BuyNowCommand(idempotencyKey, buyerId, SkuId.of(skuId))

fun InstantTradeRequest.toSellNowCommand(idempotencyKey: String, sellerId: UserId) =
    SellNowCommand(idempotencyKey, sellerId, SkuId.of(skuId))

// ── Trade ───────────────────────────────

data class TradeResponse(
    val id: String,
    val skuId: String,
    val sellerId: String,
    val buyerId: String,
    val price: Long,
    val currency: String,
    val buyerCharge: Long,
    val sellerNet: Long,
    val status: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(t: Trade) = TradeResponse(
            id = t.id().toString(),
            skuId = t.skuId().toString(),
            sellerId = t.sellerId().value(),
            buyerId = t.buyerId().value(),
            price = t.price().amount().toLong(),
            currency = t.price().currency().currencyCode,
            buyerCharge = t.feeSnapshot().buyerCharge().amount().toLong(),
            sellerNet = t.feeSnapshot().sellerNet().amount().toLong(),
            status = t.status().name,
            createdAt = t.createdAt(),
        )
    }
}

// ── OrderBook ───────────────────────────────

data class OrderBookView(
    val skuId: String,
    val lowestAsk: Long?,
    val highestBid: Long?,
    val asks: List<PriceLevel>,
    val bids: List<PriceLevel>,
) {
    companion object {
        fun from(v: OrderBookQueryUseCase.OrderBookView): OrderBookView = OrderBookView(
            skuId = v.skuId().toString(),
            lowestAsk = v.lowestAsk().getOrNull()?.amount()?.toLong(),
            highestBid = v.highestBid().getOrNull()?.amount()?.toLong(),
            asks = v.asks().map { PriceLevel(it.price().amount().toLong(), it.count()) },
            bids = v.bids().map { PriceLevel(it.price().amount().toLong(), it.count()) },
        )
    }
}

data class PriceLevel(val price: Long, val count: Int)
