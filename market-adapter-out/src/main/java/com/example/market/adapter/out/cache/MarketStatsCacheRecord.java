package com.example.market.adapter.out.cache;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.marketdata.MarketStats;
import com.example.market.domain.shared.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/**
 * Redis 직렬화 용 MarketStats DTO. {@link Money} (Currency + BigDecimal) 와 {@link SkuId} 를
 * Jackson 이 자연스럽게 다룰 수 있는 string / BigDecimal 조합으로 분해.
 *
 * <p>Redis 캐시 wire format 을 안정적으로 유지 — domain {@link MarketStats} 의 필드가 늘어나면
 * 이 record 만 손보고 마이그레이션하면 됨 (캐시는 TTL 짧아서 그냥 무효화 시 자연 소실).</p>
 */
record MarketStatsCacheRecord(
        UUID skuId,
        Instant asOf,
        BigDecimal lastTradePrice,
        String lastTradePriceCurrency,
        Instant lastTradeAt,
        BigDecimal bestBid,
        String bestBidCurrency,
        BigDecimal bestAsk,
        String bestAskCurrency,
        BigDecimal spread,
        String spreadCurrency,
        long last24hVolume,
        BigDecimal last24hMin,
        String last24hMinCurrency,
        BigDecimal last24hAvg,
        String last24hAvgCurrency,
        BigDecimal last24hMax,
        String last24hMaxCurrency
) {

    static MarketStatsCacheRecord from(MarketStats s) {
        return new MarketStatsCacheRecord(
                s.skuId().value(),
                s.asOf(),
                amount(s.lastTradePrice()),
                currency(s.lastTradePrice()),
                s.lastTradeAt(),
                amount(s.bestBid()),
                currency(s.bestBid()),
                amount(s.bestAsk()),
                currency(s.bestAsk()),
                amount(s.spread()),
                currency(s.spread()),
                s.last24hVolume(),
                amount(s.last24hMin()),
                currency(s.last24hMin()),
                amount(s.last24hAvg()),
                currency(s.last24hAvg()),
                amount(s.last24hMax()),
                currency(s.last24hMax())
        );
    }

    MarketStats toDomain() {
        return new MarketStats(
                SkuId.of(skuId.toString()),
                asOf,
                money(lastTradePrice, lastTradePriceCurrency),
                lastTradeAt,
                money(bestBid, bestBidCurrency),
                money(bestAsk, bestAskCurrency),
                money(spread, spreadCurrency),
                last24hVolume,
                money(last24hMin, last24hMinCurrency),
                money(last24hAvg, last24hAvgCurrency),
                money(last24hMax, last24hMaxCurrency)
        );
    }

    private static BigDecimal amount(Money m) { return m == null ? null : m.amount(); }
    private static String currency(Money m)  { return m == null ? null : m.currency().getCurrencyCode(); }
    private static Money money(BigDecimal amount, String currency) {
        if (amount == null || currency == null) return null;
        return Money.of(amount, Currency.getInstance(currency));
    }
}
