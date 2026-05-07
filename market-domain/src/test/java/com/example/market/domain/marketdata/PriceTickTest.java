package com.example.market.domain.marketdata;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.shared.Money;
import com.example.market.domain.trading.TradeId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceTickTest {

    private static final Currency KRW = Currency.getInstance("KRW");

    @Test
    void from_assignsRandomIdAndCopiesFields() {
        TradeId tradeId = TradeId.of(UUID.randomUUID().toString());
        SkuId skuId = SkuId.of(UUID.randomUUID().toString());
        Money price = Money.of(BigDecimal.valueOf(180_000), KRW);
        Instant at = Instant.parse("2026-05-01T12:00:00Z");

        PriceTick tick = PriceTick.from(tradeId, skuId, price, at);

        assertThat(tick.id()).isNotNull();
        assertThat(tick.tradeId()).isEqualTo(tradeId);
        assertThat(tick.skuId()).isEqualTo(skuId);
        assertThat(tick.price()).isEqualTo(price);
        assertThat(tick.occurredAt()).isEqualTo(at);
    }

    @Test
    void rejectsZeroOrNegativePrice() {
        TradeId tradeId = TradeId.of(UUID.randomUUID().toString());
        SkuId skuId = SkuId.of(UUID.randomUUID().toString());
        Money zero = Money.of(BigDecimal.ZERO, KRW);

        assertThatThrownBy(() -> PriceTick.from(tradeId, skuId, zero, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void rejectsNullArguments() {
        TradeId tradeId = TradeId.of(UUID.randomUUID().toString());
        SkuId skuId = SkuId.of(UUID.randomUUID().toString());
        Money price = Money.of(BigDecimal.valueOf(100), KRW);
        Instant at = Instant.now();

        assertThatThrownBy(() -> new PriceTick(null, tradeId, skuId, price, at))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PriceTick(UUID.randomUUID(), null, skuId, price, at))
                .isInstanceOf(NullPointerException.class);
    }
}
