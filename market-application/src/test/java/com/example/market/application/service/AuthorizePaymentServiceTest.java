package com.example.market.application.service;

import com.example.market.application.command.AuthorizePaymentCommand;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.PgClient;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizePaymentServiceTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");
    private static final SkuId SKU = SkuId.newId();
    private static final UserId SELLER = UserId.of("seller");
    private static final UserId BUYER = UserId.of("buyer");
    private static final FeePolicy POLICY = new FeePolicy(
            new BigDecimal("3.0"), new BigDecimal("3.5"),
            money(3_000), money(3_000), money(1_000));

    private TradeRepository trades;
    private PgClient pgClient;
    private EventPublisher events;
    private AuthorizePaymentService service;

    @BeforeEach
    void setUp() {
        trades = mock(TradeRepository.class);
        pgClient = mock(PgClient.class);
        events = mock(EventPublisher.class);
        service = new AuthorizePaymentService(trades, pgClient, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void authorize_pgApproved_setsTradeAuthorized() {
        Trade trade = newTrade();
        when(trades.findById(trade.id())).thenReturn(Optional.of(trade));
        when(pgClient.authorize(any())).thenReturn(
                PgClient.AuthorizeResult.approved("pg-tx-1"));

        Trade result = service.authorize(new AuthorizePaymentCommand(trade.id()));

        assertThat(result.status()).isEqualTo(TradeStatus.PAYMENT_AUTHORIZED);
        assertThat(result.pgPaymentId()).isEqualTo("pg-tx-1");
        verify(trades).save(trade);
        verify(events).publish(any(Trade.PaymentAuthorized.class));
    }

    @Test
    void authorize_pgRejected_cancelsOnPaymentFailure() {
        Trade trade = newTrade();
        when(trades.findById(trade.id())).thenReturn(Optional.of(trade));
        when(pgClient.authorize(any())).thenReturn(
                PgClient.AuthorizeResult.rejected("INSUFFICIENT_FUNDS", "card declined"));

        Trade result = service.authorize(new AuthorizePaymentCommand(trade.id()));

        assertThat(result.status()).isEqualTo(TradeStatus.FAILED);
        verify(trades).save(trade);
        verify(events).publish(any(Trade.PaymentRejected.class));
    }

    @Test
    void authorize_alreadyAuthorized_isIdempotentSkip() {
        Trade trade = newTrade();
        trade.authorizePayment("pg-old", NOW);  // 이미 authorized 상태
        when(trades.findById(trade.id())).thenReturn(Optional.of(trade));

        service.authorize(new AuthorizePaymentCommand(trade.id()));

        // PG 재호출 안됨, save/publish 안됨
        verify(pgClient, never()).authorize(any());
        verify(trades, never()).save(any());
        verify(events, never()).publish(any());
    }

    private Trade newTrade() {
        Listing ask = Listing.place(SKU, SELLER, money(140_000), NOW);
        Bid bid = Bid.place(SKU, BUYER, money(150_000), NOW);
        return Trade.match(ask, bid, money(150_000), POLICY, NOW);
    }

    private static Money money(long won) {
        return Money.of(BigDecimal.valueOf(won), KRW);
    }
}
