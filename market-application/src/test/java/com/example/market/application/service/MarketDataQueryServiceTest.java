package com.example.market.application.service;

import com.example.market.application.port.in.OrderBookQueryUseCase;
import com.example.market.application.port.in.OrderBookQueryUseCase.OrderBookView;
import com.example.market.application.port.out.PriceTickRepository;
import com.example.market.application.port.out.PriceTickRepository.PriceAggregation;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.marketdata.MarketStats;
import com.example.market.domain.marketdata.PriceTick;
import com.example.market.domain.shared.Money;
import com.example.market.domain.trading.TradeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketDataQueryServiceTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final SkuId SKU = SkuId.of(UUID.randomUUID().toString());

    @Mock PriceTickRepository ticks;
    @Mock OrderBookQueryUseCase orderBookQuery;

    MarketDataQueryService service;

    @BeforeEach
    void setUp() {
        service = new MarketDataQueryService(ticks, orderBookQuery, CLOCK);
    }

    private static Money won(long n) {
        return Money.of(BigDecimal.valueOf(n), KRW);
    }

    private static OrderBookView orderBook(Money bestBid, Money bestAsk) {
        return new OrderBookView(SKU,
                Optional.ofNullable(bestAsk),
                Optional.ofNullable(bestBid),
                List.of(), List.of());
    }

    @Test
    void neverTraded_lastIsNull_volumeZero_spreadFromBookOnly() {
        when(ticks.findLatest(SKU)).thenReturn(Optional.empty());
        when(orderBookQuery.view(eq(SKU), any(int.class)))
                .thenReturn(orderBook(won(150_000), won(160_000)));
        when(ticks.aggregate(eq(SKU), any(), any()))
                .thenReturn(new PriceAggregation(0L, null, null, null));

        MarketStats s = service.currentStats(SKU);

        assertThat(s.lastTradePrice()).isNull();
        assertThat(s.lastTradeAt()).isNull();
        assertThat(s.bestBid()).isEqualTo(won(150_000));
        assertThat(s.bestAsk()).isEqualTo(won(160_000));
        assertThat(s.spread()).isEqualTo(won(10_000));
        assertThat(s.last24hVolume()).isZero();
        assertThat(s.last24hMin()).isNull();
    }

    @Test
    void withTrades_includesLastAnd24hStats() {
        TradeId tradeId = TradeId.of(UUID.randomUUID().toString());
        PriceTick last = new PriceTick(UUID.randomUUID(), tradeId, SKU, won(180_000),
                NOW.minus(Duration.ofMinutes(10)));
        when(ticks.findLatest(SKU)).thenReturn(Optional.of(last));
        when(orderBookQuery.view(eq(SKU), any(int.class)))
                .thenReturn(orderBook(won(170_000), won(190_000)));
        when(ticks.aggregate(eq(SKU), any(), any()))
                .thenReturn(new PriceAggregation(7L, won(170_000), won(180_000), won(195_000)));

        MarketStats s = service.currentStats(SKU);

        assertThat(s.lastTradePrice()).isEqualTo(won(180_000));
        assertThat(s.lastTradeAt()).isEqualTo(NOW.minus(Duration.ofMinutes(10)));
        assertThat(s.last24hVolume()).isEqualTo(7L);
        assertThat(s.last24hMin()).isEqualTo(won(170_000));
        assertThat(s.last24hAvg()).isEqualTo(won(180_000));
        assertThat(s.last24hMax()).isEqualTo(won(195_000));
        assertThat(s.spread()).isEqualTo(won(20_000));
    }

    @Test
    void onlyBidNoAsk_spreadIsNull() {
        when(ticks.findLatest(SKU)).thenReturn(Optional.empty());
        when(orderBookQuery.view(eq(SKU), any(int.class)))
                .thenReturn(orderBook(won(150_000), null));
        when(ticks.aggregate(eq(SKU), any(), any()))
                .thenReturn(new PriceAggregation(0L, null, null, null));

        MarketStats s = service.currentStats(SKU);

        assertThat(s.bestBid()).isEqualTo(won(150_000));
        assertThat(s.bestAsk()).isNull();
        assertThat(s.spread()).isNull();
    }

    @Test
    void asOfReflectsClock() {
        when(ticks.findLatest(SKU)).thenReturn(Optional.empty());
        when(orderBookQuery.view(eq(SKU), any(int.class)))
                .thenReturn(orderBook(null, null));
        when(ticks.aggregate(eq(SKU), any(), any()))
                .thenReturn(new PriceAggregation(0L, null, null, null));

        MarketStats s = service.currentStats(SKU);
        assertThat(s.asOf()).isEqualTo(NOW);
    }
}
