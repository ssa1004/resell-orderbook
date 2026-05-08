package com.example.market.application.service;

import com.example.market.application.exception.PgFailureException;
import com.example.market.application.port.out.CompensationLogStore;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.PgClient;
import com.example.market.application.port.out.RefundRepository;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.settlement.FeePolicy;
import com.example.market.domain.settlement.RefundStatus;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundBuyerServiceTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");
    private static final SkuId SKU = SkuId.newId();
    private static final UserId SELLER = UserId.of("seller");
    private static final UserId BUYER = UserId.of("buyer");
    private static final FeePolicy POLICY = new FeePolicy(
            new BigDecimal("3.0"), new BigDecimal("3.5"),
            money(3_000), money(3_000), money(1_000));

    private TradeRepository trades;
    private RefundRepository refunds;
    private PgClient pgClient;
    private EventPublisher events;
    private RefundBuyerService service;

    @BeforeEach
    void setUp() {
        trades = mock(TradeRepository.class);
        refunds = mock(RefundRepository.class);
        pgClient = mock(PgClient.class);
        events = mock(EventPublisher.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        // 테스트는 in-memory CompensationLogStore — 매 테스트마다 빈 상태로 시작.
        CompensationGuard guard = new CompensationGuard(new InMemoryCompensationLogStore(), clock);
        service = new RefundBuyerService(trades, refunds, pgClient, events, guard, clock);
    }

    /** 테스트 전용 in-memory store — JPA 기반 store 의 PK 충돌 / cache 거동을 simulate. */
    static final class InMemoryCompensationLogStore implements CompensationLogStore {
        private final java.util.Map<String, Entry> rows = new java.util.concurrent.ConcurrentHashMap<>();

        private static String k(String op, String key) { return op + "|" + key; }

        @Override
        public void begin(String operation, String businessKey, java.time.Instant now) {
            var prev = rows.putIfAbsent(k(operation, businessKey),
                    new Entry(operation, businessKey, Status.IN_PROGRESS, null, null, null, now, null));
            if (prev != null) throw new DuplicateBeginException(operation, businessKey);
        }

        @Override
        public void complete(String operation, String businessKey, String code, String msg, String externalId, java.time.Instant now) {
            rows.put(k(operation, businessKey),
                    new Entry(operation, businessKey, Status.COMPLETED, code, msg, externalId,
                            rows.get(k(operation, businessKey)).startedAt(), now));
        }

        @Override
        public void fail(String operation, String businessKey, String code, String msg, java.time.Instant now) {
            rows.put(k(operation, businessKey),
                    new Entry(operation, businessKey, Status.FAILED, code, msg, null,
                            rows.get(k(operation, businessKey)).startedAt(), now));
        }

        @Override
        public java.util.Optional<Entry> find(String operation, String businessKey) {
            return java.util.Optional.ofNullable(rows.get(k(operation, businessKey)));
        }
    }

    @Test
    void refund_pgApproved_completesAndClosesTrade() {
        Trade trade = tradeAtInspectionFailed();
        when(trades.findById(trade.id())).thenReturn(Optional.of(trade));
        when(refunds.findByTradeId(trade.id())).thenReturn(Optional.empty());
        when(pgClient.refund(any())).thenReturn(PgClient.RefundResult.approved("pg-refund-1"));

        var refund = service.refund(trade.id());

        assertThat(refund.status()).isEqualTo(RefundStatus.COMPLETED);
        // 환불액 = buyerCharge (검수비/배송비 포함 전액)
        assertThat(refund.amount()).isEqualTo(trade.feeSnapshot().buyerCharge());
        assertThat(trade.status()).isEqualTo(TradeStatus.FAILED);
    }

    @Test
    void refund_pgFailed_throwsAndKeepsTradeInRefunding() {
        Trade trade = tradeAtInspectionFailed();
        when(trades.findById(trade.id())).thenReturn(Optional.of(trade));
        when(refunds.findByTradeId(trade.id())).thenReturn(Optional.empty());
        when(pgClient.refund(any())).thenReturn(PgClient.RefundResult.rejected("pg unavailable"));

        assertThatThrownBy(() -> service.refund(trade.id()))
                .isInstanceOf(PgFailureException.class);

        // Trade 는 REFUNDING 에 머무름 — 운영자 RetryRefund 대상
        assertThat(trade.status()).isEqualTo(TradeStatus.REFUNDING);
    }

    @Test
    void refund_existingRefund_isIdempotent() {
        Trade trade = tradeAtInspectionFailed();
        // 이미 refund 가 있는 시나리오 (컨슈머 at-least-once 중복)
        var existing = com.example.market.domain.settlement.Refund.request(
                trade.id(), BUYER, trade.feeSnapshot().buyerCharge(), "fake", NOW);
        when(trades.findById(trade.id())).thenReturn(Optional.of(trade));
        when(refunds.findByTradeId(trade.id())).thenReturn(Optional.of(existing));

        var result = service.refund(trade.id());

        assertThat(result).isSameAs(existing);
        verify(pgClient, never()).refund(any());
    }

    private Trade tradeAtInspectionFailed() {
        Listing ask = Listing.place(SKU, SELLER, money(140_000), NOW);
        Bid bid = Bid.place(SKU, BUYER, money(150_000), NOW);
        Trade t = Trade.match(ask, bid, money(150_000), POLICY, NOW);
        t.authorizePayment("pg", NOW);
        t.startSellerShipping(NOW);
        t.arriveAtInspection(NOW);
        t.failInspection("fake item", NOW);
        return t;
    }

    private static Money money(long won) {
        return Money.of(BigDecimal.valueOf(won), KRW);
    }
}
