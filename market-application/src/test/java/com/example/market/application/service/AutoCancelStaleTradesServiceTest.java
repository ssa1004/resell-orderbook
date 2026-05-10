package com.example.market.application.service;

import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.settlement.FeePolicy;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.Bid;
import com.example.market.domain.trading.Listing;
import com.example.market.domain.trading.Trade;
import com.example.market.domain.trading.TradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoCancelStaleTradesServiceTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");
    private static final SkuId SKU = SkuId.newId();
    private static final FeePolicy POLICY = new FeePolicy(
            new BigDecimal("3.0"), new BigDecimal("3.5"),
            money(3_000), money(3_000), money(1_000));

    private TradeRepository trades;
    private EventPublisher events;
    private AutoCancelStaleTradesService service;

    @BeforeEach
    void setUp() {
        trades = mock(TradeRepository.class);
        events = mock(EventPublisher.class);
        service = new AutoCancelStaleTradesService(trades, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void cancelStale_setsAllStaleTradesToFailed() {
        Trade t1 = createdTrade();
        Trade t2 = createdTrade();
        when(trades.findStaleCreated(any(), anyInt())).thenReturn(List.of(t1, t2));

        int count = service.cancelStale(Duration.ofMinutes(15), 100);

        assertThat(count).isEqualTo(2);
        assertThat(t1.status()).isEqualTo(TradeStatus.FAILED);
        assertThat(t2.status()).isEqualTo(TradeStatus.FAILED);
        verify(events, times(2)).publish(any(Trade.PaymentRejected.class));
    }

    @Test
    void cancelStale_emptyResult_returnsZero() {
        when(trades.findStaleCreated(any(), anyInt())).thenReturn(List.of());

        int count = service.cancelStale(Duration.ofMinutes(15), 100);

        assertThat(count).isEqualTo(0);
    }

    @Test
    void cancelStale_passesBatchSizeAsFetchLimit() {
        // 회귀 테스트 — 이전엔 repository 가 200 row hard-coded fetch 였어서 caller 가 500 을
        // 보내도 200 만 fetch 되었다. 지금은 batchSize 가 그대로 limit 으로 흘러야 한다.
        when(trades.findStaleCreated(any(), eq(500))).thenReturn(List.of());

        service.cancelStale(Duration.ofMinutes(15), 500);

        verify(trades).findStaleCreated(any(), eq(500));
    }

    @Test
    void cancelStale_rejectsNonPositiveBatchSize() {
        assertThatThrownBy(() -> service.cancelStale(Duration.ofMinutes(15), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.cancelStale(Duration.ofMinutes(15), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Trade createdTrade() {
        Listing ask = Listing.place(SKU, UserId.of("seller-" + System.nanoTime()), money(140_000), NOW);
        Bid bid = Bid.place(SKU, UserId.of("buyer-" + System.nanoTime()), money(150_000), NOW);
        return Trade.match(ask, bid, money(150_000), POLICY, NOW);
    }

    private static Money money(long won) {
        return Money.of(BigDecimal.valueOf(won), KRW);
    }
}
