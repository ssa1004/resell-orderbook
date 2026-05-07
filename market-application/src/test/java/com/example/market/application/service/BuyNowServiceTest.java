package com.example.market.application.service;

import com.example.market.application.command.BuyNowCommand;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.FeePolicyProvider;
import com.example.market.application.port.out.IdempotencyKeyStore;
import com.example.market.application.port.out.ListingRepository;
import com.example.market.application.port.out.OrderBookQueryPort;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.settlement.FeePolicy;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.Listing;
import com.example.market.domain.trading.ListingStatus;
import com.example.market.domain.trading.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuyNowServiceTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");
    private static final SkuId SKU = SkuId.newId();
    private static final UserId SELLER = UserId.of("seller");
    private static final UserId BUYER = UserId.of("buyer");
    private static final FeePolicy POLICY = new FeePolicy(
            new BigDecimal("3.0"), new BigDecimal("3.5"),
            money(3_000), money(3_000), money(1_000));

    private ListingRepository listings;
    private TradeRepository trades;
    private OrderBookQueryPort orderBook;
    private EventPublisher events;
    private IdempotencyKeyStore idempotency;
    private FeePolicyProvider feeProvider;
    private com.example.market.application.port.out.PriceTickRepository priceTicks;
    private BuyNowService service;

    @BeforeEach
    void setUp() {
        listings = mock(ListingRepository.class);
        trades = mock(TradeRepository.class);
        orderBook = mock(OrderBookQueryPort.class);
        events = mock(EventPublisher.class);
        idempotency = mock(IdempotencyKeyStore.class);
        feeProvider = mock(FeePolicyProvider.class);
        priceTicks = mock(com.example.market.application.port.out.PriceTickRepository.class);
        when(feeProvider.current()).thenReturn(POLICY);
        service = new BuyNowService(listings, trades, orderBook, events, idempotency, feeProvider,
                priceTicks, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void buyNow_matchesAtAskPrice() {
        Listing ask = Listing.place(SKU, SELLER, money(140_000), NOW);
        when(orderBook.findLowestAskForUpdate(eq(SKU), any())).thenReturn(Optional.of(ask));

        Trade trade = service.buyNow(new BuyNowCommand("key-1", BUYER, SKU));

        // BuyNow → ASK 가격으로 매칭 (taker 가 ASK 수용)
        assertThat(trade.price()).isEqualTo(money(140_000));
        assertThat(trade.buyerId()).isEqualTo(BUYER);
        assertThat(trade.sellerId()).isEqualTo(SELLER);
        assertThat(ask.status()).isEqualTo(ListingStatus.MATCHED);

        verify(orderBook).acquireSkuLock(SKU);
        verify(listings).save(ask);
        verify(trades).save(trade);
        verify(events).publish(any(Trade.TradeMatched.class));
    }

    @Test
    void buyNow_noActiveAsk_throwsAndDoesNotPublish() {
        when(orderBook.findLowestAskForUpdate(eq(SKU), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.buyNow(new BuyNowCommand("key-2", BUYER, SKU)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active ASK");

        verify(events, org.mockito.Mockito.never()).publish(any());
    }

    @Test
    void buyNow_ownListing_throwsSelfBuyFromMatchEngine() {
        Listing ownAsk = Listing.place(SKU, BUYER, money(140_000), NOW);
        when(orderBook.findLowestAskForUpdate(eq(SKU), any())).thenReturn(Optional.of(ownAsk));

        assertThatThrownBy(() -> service.buyNow(new BuyNowCommand("key-3", BUYER, SKU)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot buy own listing");
    }

    private static Money money(long won) {
        return Money.of(BigDecimal.valueOf(won), KRW);
    }
}
