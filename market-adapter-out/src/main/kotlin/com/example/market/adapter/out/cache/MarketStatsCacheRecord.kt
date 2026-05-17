package com.example.market.adapter.out.cache

import com.example.market.domain.catalog.SkuId
import com.example.market.domain.marketdata.MarketStats
import com.example.market.domain.shared.Money
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.UUID

/**
 * Redis 직렬화 용 MarketStats DTO. [Money] (Currency + BigDecimal) 와 [SkuId] 를
 * Jackson 이 자연스럽게 다룰 수 있는 string / BigDecimal 조합으로 분해.
 *
 * Redis 캐시 wire format 을 안정적으로 유지 — domain [MarketStats] 의 필드가 늘어나면
 * 이 record 만 손보고 마이그레이션하면 됨 (캐시는 TTL 짧아서 그냥 무효화 시 자연 소실).
 */
@JvmRecord
internal data class MarketStatsCacheRecord(
    val skuId: UUID,
    val asOf: Instant,
    val lastTradePrice: BigDecimal?,
    val lastTradePriceCurrency: String?,
    val lastTradeAt: Instant?,
    val bestBid: BigDecimal?,
    val bestBidCurrency: String?,
    val bestAsk: BigDecimal?,
    val bestAskCurrency: String?,
    val spread: BigDecimal?,
    val spreadCurrency: String?,
    val last24hVolume: Long,
    val last24hMin: BigDecimal?,
    val last24hMinCurrency: String?,
    val last24hAvg: BigDecimal?,
    val last24hAvgCurrency: String?,
    val last24hMax: BigDecimal?,
    val last24hMaxCurrency: String?,
) {

    fun toDomain(): MarketStats = MarketStats(
        SkuId(skuId),
        asOf,
        money(lastTradePrice, lastTradePriceCurrency),
        lastTradeAt,
        money(bestBid, bestBidCurrency),
        money(bestAsk, bestAskCurrency),
        money(spread, spreadCurrency),
        last24hVolume,
        money(last24hMin, last24hMinCurrency),
        money(last24hAvg, last24hAvgCurrency),
        money(last24hMax, last24hMaxCurrency),
    )

    companion object {
        @JvmStatic
        fun from(s: MarketStats): MarketStatsCacheRecord = MarketStatsCacheRecord(
            s.skuId.value,
            s.asOf,
            amount(s.lastTradePrice),
            currency(s.lastTradePrice),
            s.lastTradeAt,
            amount(s.bestBid),
            currency(s.bestBid),
            amount(s.bestAsk),
            currency(s.bestAsk),
            amount(s.spread),
            currency(s.spread),
            s.last24hVolume,
            amount(s.last24hMin),
            currency(s.last24hMin),
            amount(s.last24hAvg),
            currency(s.last24hAvg),
            amount(s.last24hMax),
            currency(s.last24hMax),
        )

        private fun amount(m: Money?): BigDecimal? = m?.amount
        private fun currency(m: Money?): String? = m?.currency?.currencyCode
        private fun money(amount: BigDecimal?, currency: String?): Money? {
            if (amount == null || currency == null) return null
            return Money.of(amount, Currency.getInstance(currency))
        }
    }
}
