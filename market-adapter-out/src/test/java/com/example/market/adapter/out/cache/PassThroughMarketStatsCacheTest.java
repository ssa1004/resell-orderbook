package com.example.market.adapter.out.cache;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.marketdata.MarketStats;
import com.example.market.domain.shared.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PassThroughMarketStatsCacheTest {

    private static final Currency KRW = Currency.getInstance("KRW");

    @Test
    void getOrCompute_alwaysCallsLoader() {
        PassThroughMarketStatsCache cache = new PassThroughMarketStatsCache();
        SkuId sku = SkuId.of(UUID.randomUUID().toString());
        Instant now = Instant.parse("2026-05-08T12:00:00Z");
        Money price = Money.of(BigDecimal.valueOf(150_000), KRW);
        AtomicInteger calls = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            MarketStats v = cache.getOrCompute(sku, () -> {
                calls.incrementAndGet();
                return new MarketStats(sku, now, price, now, null, null, null, 1L, price, price, price);
            });
            assertThat(v.lastTradePrice()).isEqualTo(price);
        }
        assertThat(calls.get()).isEqualTo(5);   // pass-through — loader 매번 호출
    }

    @Test
    void invalidate_isNoop() {
        PassThroughMarketStatsCache cache = new PassThroughMarketStatsCache();
        cache.invalidate(SkuId.of(UUID.randomUUID().toString()));
        // 예외 없이 통과
    }
}
