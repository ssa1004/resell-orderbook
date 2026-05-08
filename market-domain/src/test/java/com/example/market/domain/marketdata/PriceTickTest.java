package com.example.market.domain.marketdata;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.SnowflakeIdGenerator;
import com.example.market.domain.trading.TradeId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceTickTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static SnowflakeIdGenerator gen() {
        return new SnowflakeIdGenerator(1, CLOCK);
    }

    @Test
    void from_assignsSnowflakeIdAndCopiesFields() {
        TradeId tradeId = TradeId.of(UUID.randomUUID().toString());
        SkuId skuId = SkuId.of(UUID.randomUUID().toString());
        Money price = Money.of(BigDecimal.valueOf(180_000), KRW);

        PriceTick tick = PriceTick.from(gen(), tradeId, skuId, price, NOW);

        assertThat(tick.id()).isPositive();
        // 디코딩한 timestamp 가 입력 시각과 같은 ms 인지 확인 — Snowflake encoding 정상.
        assertThat(SnowflakeIdGenerator.timestampOf(tick.id())).isEqualTo(NOW);
        assertThat(tick.tradeId()).isEqualTo(tradeId);
        assertThat(tick.skuId()).isEqualTo(skuId);
        assertThat(tick.price()).isEqualTo(price);
        assertThat(tick.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsZeroOrNegativePrice() {
        TradeId tradeId = TradeId.of(UUID.randomUUID().toString());
        SkuId skuId = SkuId.of(UUID.randomUUID().toString());
        Money zero = Money.of(BigDecimal.ZERO, KRW);

        assertThatThrownBy(() -> PriceTick.from(gen(), tradeId, skuId, zero, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void rejectsNonPositiveId() {
        TradeId tradeId = TradeId.of(UUID.randomUUID().toString());
        SkuId skuId = SkuId.of(UUID.randomUUID().toString());
        Money price = Money.of(BigDecimal.valueOf(100), KRW);

        assertThatThrownBy(() -> new PriceTick(0L, tradeId, skuId, price, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snowflake");
        assertThatThrownBy(() -> new PriceTick(-1L, tradeId, skuId, price, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullArguments() {
        TradeId tradeId = TradeId.of(UUID.randomUUID().toString());
        SkuId skuId = SkuId.of(UUID.randomUUID().toString());
        Money price = Money.of(BigDecimal.valueOf(100), KRW);

        assertThatThrownBy(() -> new PriceTick(1L, null, skuId, price, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PriceTick(1L, tradeId, null, price, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void idsFromSameGenerator_areMonotonicallyIncreasing() {
        SnowflakeIdGenerator gen = gen();
        TradeId t1 = TradeId.of(UUID.randomUUID().toString());
        TradeId t2 = TradeId.of(UUID.randomUUID().toString());
        SkuId skuId = SkuId.of(UUID.randomUUID().toString());
        Money price = Money.of(BigDecimal.valueOf(100), KRW);

        PriceTick first = PriceTick.from(gen, t1, skuId, price, NOW);
        PriceTick second = PriceTick.from(gen, t2, skuId, price, NOW);

        assertThat(second.id()).isGreaterThan(first.id());
    }
}
