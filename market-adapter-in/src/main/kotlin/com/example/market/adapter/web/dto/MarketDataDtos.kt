package com.example.market.adapter.web.dto

import java.math.BigDecimal

/**
 * 한 SKU 의 시세 카드 — 사용자 화면 / 운영자 dashboard 의 한 응답.
 *
 * 모든 Money 필드는 *없을 수 있다* — 한 번도 거래된 적 없는 SKU 는 last/min/avg/max 가 null,
 * 호가 한쪽이 없으면 best bid 또는 best ask 가 null.
 */
data class MarketStatsResponse(
    val skuId: String,
    val asOf: String,
    val currency: String,
    val lastTradePrice: BigDecimal?,
    val lastTradeAt: String?,
    val bestBid: BigDecimal?,
    val bestAsk: BigDecimal?,
    val spread: BigDecimal?,
    val last24hVolume: Long,
    val last24hMin: BigDecimal?,
    val last24hAvg: BigDecimal?,
    val last24hMax: BigDecimal?,
)

/** 차트 한 점 — 시간 + 가격. */
data class PriceTickView(
    val tradeId: String,
    val price: BigDecimal,
    val occurredAt: String,
)

data class PriceTicksResponse(
    val skuId: String,
    val from: String,
    val to: String,
    val ticks: List<PriceTickView>,
)
