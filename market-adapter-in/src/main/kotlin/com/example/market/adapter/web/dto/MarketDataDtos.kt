package com.example.market.adapter.web.dto

import com.example.market.domain.marketdata.MarketStats
import com.example.market.domain.marketdata.OhlcCandle
import com.example.market.domain.marketdata.PriceTick
import java.math.BigDecimal

private const val DEFAULT_CURRENCY = "KRW"

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
) {
    companion object {
        fun from(s: MarketStats): MarketStatsResponse = MarketStatsResponse(
            skuId = s.skuId().value.toString(),
            asOf = s.asOf().toString(),
            // 통화는 가격 필드에서 — 없으면 KRW default
            currency = (s.lastTradePrice() ?: s.bestBid() ?: s.bestAsk())
                ?.currency()?.currencyCode ?: DEFAULT_CURRENCY,
            lastTradePrice = s.lastTradePrice()?.amount(),
            lastTradeAt = s.lastTradeAt()?.toString(),
            bestBid = s.bestBid()?.amount(),
            bestAsk = s.bestAsk()?.amount(),
            spread = s.spread()?.amount(),
            last24hVolume = s.last24hVolume(),
            last24hMin = s.last24hMin()?.amount(),
            last24hAvg = s.last24hAvg()?.amount(),
            last24hMax = s.last24hMax()?.amount(),
        )
    }
}

/** 차트 한 점 — 시간 + 가격. */
data class PriceTickView(
    val tradeId: String,
    val price: BigDecimal,
    val occurredAt: String,
) {
    companion object {
        fun from(t: PriceTick): PriceTickView = PriceTickView(
            tradeId = t.tradeId().toString(),
            price = t.price().amount(),
            occurredAt = t.occurredAt().toString(),
        )
    }
}

data class PriceTicksResponse(
    val skuId: String,
    val from: String,
    val to: String,
    val ticks: List<PriceTickView>,
)

/**
 * 캔들스틱 차트 1개 막대 — 주식 차트의 표준 형태.
 * Frontend 차트 라이브러리 (TradingView / Highcharts) 가 그대로 받아 그릴 수 있다.
 */
data class OhlcCandleView(
    val bucketStart: String,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
    val tradeCount: Long,
) {
    companion object {
        fun from(c: OhlcCandle): OhlcCandleView = OhlcCandleView(
            bucketStart = c.bucketStart().toString(),
            open = c.open().amount(),
            high = c.high().amount(),
            low = c.low().amount(),
            close = c.close().amount(),
            volume = c.volume(),
            tradeCount = c.tradeCount(),
        )
    }
}

data class OhlcCandlesResponse(
    val skuId: String,
    val period: String,           // ONE_MIN / FIVE_MIN / ONE_HOUR / ONE_DAY
    val from: String,
    val to: String,
    val currency: String,
    val candles: List<OhlcCandleView>,
) {
    companion object {
        /** 첫 캔들의 통화를 기준으로 설정. 비어 있으면 KRW. */
        fun of(
            skuId: String,
            period: String,
            from: String,
            to: String,
            candles: List<OhlcCandle>,
        ): OhlcCandlesResponse = OhlcCandlesResponse(
            skuId = skuId,
            period = period,
            from = from,
            to = to,
            currency = candles.firstOrNull()?.open()?.currency()?.currencyCode ?: DEFAULT_CURRENCY,
            candles = candles.map(OhlcCandleView::from),
        )
    }
}
