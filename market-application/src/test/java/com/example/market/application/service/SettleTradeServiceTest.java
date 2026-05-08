package com.example.market.application.service;

import com.example.market.application.port.out.BankTransferClient;
import com.example.market.application.port.out.CompensationLogStore;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.PayoutRepository;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.settlement.FeePolicy;
import com.example.market.domain.settlement.PayoutStatus;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.Bid;
import com.example.market.domain.trading.Listing;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettleTradeServiceTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");
    private static final SkuId SKU = SkuId.newId();
    private static final UserId SELLER = UserId.of("seller");
    private static final UserId BUYER = UserId.of("buyer");
    private static final FeePolicy POLICY = new FeePolicy(
            new BigDecimal("3.0"), new BigDecimal("3.5"),
            money(3_000), money(3_000), money(1_000));

    private TradeRepository trades;
    private PayoutRepository payouts;
    private BankTransferClient bank;
    private EventPublisher events;
    private SettleTradeService service;

    @BeforeEach
    void setUp() {
        trades = mock(TradeRepository.class);
        payouts = mock(PayoutRepository.class);
        bank = mock(BankTransferClient.class);
        events = mock(EventPublisher.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        CompensationGuard guard = new CompensationGuard(new InMemoryCompensationLogStore(), clock);
        service = new SettleTradeService(trades, payouts, bank, events, guard, clock);
    }

    /** in-memory store — RefundBuyerServiceTest 의 InMemoryCompensationLogStore 와 동등. */
    static final class InMemoryCompensationLogStore implements CompensationLogStore {
        private final java.util.Map<String, Entry> rows = new java.util.concurrent.ConcurrentHashMap<>();
        private static String k(String op, String key) { return op + "|" + key; }

        @Override public void begin(String op, String key, java.time.Instant now) {
            var prev = rows.putIfAbsent(k(op, key),
                    new Entry(op, key, Status.IN_PROGRESS, null, null, null, now, null));
            if (prev != null) throw new DuplicateBeginException(op, key);
        }
        @Override public void complete(String op, String key, String code, String msg, String externalId, java.time.Instant now) {
            rows.put(k(op, key),
                    new Entry(op, key, Status.COMPLETED, code, msg, externalId,
                            rows.get(k(op, key)).startedAt(), now));
        }
        @Override public void fail(String op, String key, String code, String msg, java.time.Instant now) {
            rows.put(k(op, key),
                    new Entry(op, key, Status.FAILED, code, msg, null,
                            rows.get(k(op, key)).startedAt(), now));
        }
        @Override public java.util.Optional<Entry> find(String op, String key) {
            return java.util.Optional.ofNullable(rows.get(k(op, key)));
        }
    }

    @Test
    void settle_calculatesNetAndSendsToBank() {
        Trade trade = completedTrade();
        when(trades.findById(trade.id())).thenReturn(Optional.of(trade));
        when(payouts.findByTradeId(trade.id())).thenReturn(Optional.empty());
        when(bank.send(any())).thenReturn(BankTransferClient.SendResult.accepted("bank-1"));

        var payout = service.settle(trade.id());

        // sellerNet = 150,000 - 3% - 1,000 = 144,500
        assertThat(payout.netAmount().amount()).isEqualByComparingTo("144500");
        assertThat(payout.status()).isEqualTo(PayoutStatus.SENT);
    }

    @Test
    void settle_existingPayout_isIdempotent() {
        Trade trade = completedTrade();
        var existing = com.example.market.domain.settlement.Payout.schedule(
                trade.id(), SELLER, trade.feeSnapshot(), NOW);
        when(payouts.findByTradeId(trade.id())).thenReturn(Optional.of(existing));

        var result = service.settle(trade.id());

        assertThat(result).isSameAs(existing);
        verify(bank, never()).send(any());
    }

    @Test
    void settle_bankRejected_marksPayoutFailed() {
        Trade trade = completedTrade();
        when(trades.findById(trade.id())).thenReturn(Optional.of(trade));
        when(payouts.findByTradeId(trade.id())).thenReturn(Optional.empty());
        when(bank.send(any())).thenReturn(BankTransferClient.SendResult.rejected("bank down"));

        var payout = service.settle(trade.id());

        assertThat(payout.status()).isEqualTo(PayoutStatus.FAILED);
    }

    private Trade completedTrade() {
        Listing ask = Listing.place(SKU, SELLER, money(140_000), NOW);
        Bid bid = Bid.place(SKU, BUYER, money(150_000), NOW);
        Trade t = Trade.match(ask, bid, money(150_000), POLICY, NOW);
        t.authorizePayment("pg", NOW);
        t.startSellerShipping(NOW);
        t.arriveAtInspection(NOW);
        t.passInspection(NOW);
        t.startBuyerShipping(NOW);
        t.complete(NOW);
        return t;
    }

    private static Money money(long won) {
        return Money.of(BigDecimal.valueOf(won), KRW);
    }
}
