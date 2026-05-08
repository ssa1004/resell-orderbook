package com.example.market.domain.marketdata;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.shared.Money;
import com.example.market.domain.trading.TradeId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OhlcCandleTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final SkuId SKU = SkuId.of(UUID.randomUUID().toString());
    private static final OhlcPeriod PERIOD = OhlcPeriod.ONE_HOUR;
    private static final Instant BUCKET = Instant.parse("2026-05-04T14:00:00Z");

    private static Money won(long n) { return Money.of(BigDecimal.valueOf(n), KRW); }

    private static long nextSnowflakeId = 1L;

    private static PriceTick tick(long price, Instant at) {
        return new PriceTick(nextSnowflakeId++, TradeId.of(UUID.randomUUID().toString()),
                SKU, won(price), at);
    }

    @Test
    void from_singleTickOhlcAllSame() {
        var t = tick(180_000, BUCKET.plusSeconds(30));
        var c = OhlcCandle.from(SKU, PERIOD, BUCKET, List.of(t));

        assertThat(c.open()).isEqualTo(won(180_000));
        assertThat(c.high()).isEqualTo(won(180_000));
        assertThat(c.low()).isEqualTo(won(180_000));
        assertThat(c.close()).isEqualTo(won(180_000));
        assertThat(c.volume()).isEqualTo(1L);
        assertThat(c.tradeCount()).isEqualTo(1L);
    }

    @Test
    void from_multipleTicks_correctOhlc() {
        // 시간순으로: 100k → 150k → 80k (low) → 200k (high) → 120k (close)
        var ticks = List.of(
                tick(100_000, BUCKET.plusSeconds(10)),
                tick(150_000, BUCKET.plusSeconds(20)),
                tick(80_000,  BUCKET.plusSeconds(30)),
                tick(200_000, BUCKET.plusSeconds(40)),
                tick(120_000, BUCKET.plusSeconds(50))
        );
        var c = OhlcCandle.from(SKU, PERIOD, BUCKET, ticks);

        assertThat(c.open()).isEqualTo(won(100_000));   // 첫 tick
        assertThat(c.high()).isEqualTo(won(200_000));   // 최대
        assertThat(c.low()).isEqualTo(won(80_000));     // 최소
        assertThat(c.close()).isEqualTo(won(120_000));  // 마지막 tick
        assertThat(c.volume()).isEqualTo(5L);
    }

    @Test
    void from_unsortedTicks_sortsBeforeOhlc() {
        // 시간 역순으로 입력 — 도메인이 자체 정렬
        var ticks = List.of(
                tick(120_000, BUCKET.plusSeconds(50)),   // 시간상 마지막
                tick(100_000, BUCKET.plusSeconds(10))    // 시간상 첫째
        );
        var c = OhlcCandle.from(SKU, PERIOD, BUCKET, ticks);

        assertThat(c.open()).isEqualTo(won(100_000));   // 시간 기준 첫
        assertThat(c.close()).isEqualTo(won(120_000));  // 시간 기준 마지막
    }

    @Test
    void from_emptyTicks_throws() {
        assertThatThrownBy(() -> OhlcCandle.from(SKU, PERIOD, BUCKET, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void isRising_closeGreaterThanOpen() {
        var ticks = List.of(
                tick(100_000, BUCKET.plusSeconds(10)),
                tick(150_000, BUCKET.plusSeconds(20))
        );
        assertThat(OhlcCandle.from(SKU, PERIOD, BUCKET, ticks).isRising()).isTrue();
    }

    @Test
    void changePercent_calculatedCorrectly() {
        var ticks = List.of(
                tick(100_000, BUCKET.plusSeconds(10)),
                tick(110_000, BUCKET.plusSeconds(20))
        );
        var c = OhlcCandle.from(SKU, PERIOD, BUCKET, ticks);
        // close - open = 10000, / open = 10%
        assertThat(c.changePercent()).isEqualByComparingTo("10.0000");
    }

    @Test
    void invariant_lowGreaterThanHigh_throws() {
        assertThatThrownBy(() -> new OhlcCandle(
                UUID.randomUUID(), SKU, PERIOD, BUCKET,
                won(100_000), won(50_000), won(100_000), won(80_000),   // low(100k) > high(50k)
                1L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("low > high");
    }
}
