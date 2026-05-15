package com.example.market.application.service;

import com.example.market.application.exception.PgFailureException;
import com.example.market.application.exception.RefundNotFoundException;
import com.example.market.application.port.out.CompensationLogStore;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.PgClient;
import com.example.market.application.port.out.RefundRepository;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.settlement.FeePolicy;
import com.example.market.domain.settlement.Refund;
import com.example.market.domain.settlement.RefundId;
import com.example.market.domain.settlement.RefundStatus;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.Bid;
import com.example.market.domain.trading.Listing;
import com.example.market.domain.trading.Trade;
import com.example.market.domain.trading.TradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RetryRefundService} — 운영자가 Refund.FAILED 를 PG 로 재시도하는 흐름.
 *
 * <p>핵심 검증: 재시도는 매번 새 Refund row 를 만들고, 그 새 row 의 id 를 compensation_log
 * businessKey 로 쓴다. 원래 실패한 refundId 를 키로 쓰면 1차 재시도 실패 이후 2차 재시도가
 * PG 를 다시 호출하지 못하고 stale 한 캐시 실패만 반환된다 — 이 회귀를 막는 것이
 * {@link #secondRetry_afterFirstRetryFailed_callsPgAgain()}.</p>
 */
class RetryRefundServiceTest {

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
    private RetryRefundService service;

    @BeforeEach
    void setUp() {
        trades = mock(TradeRepository.class);
        refunds = mock(RefundRepository.class);
        pgClient = mock(PgClient.class);
        events = mock(EventPublisher.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        // 매 테스트마다 빈 in-memory compensation log — JPA store 의 PK 충돌 / 캐시 거동 simulate.
        CompensationGuard guard = new CompensationGuard(new InMemoryCompensationLogStore(), clock);
        service = new RetryRefundService(refunds, trades, pgClient, events, guard, clock);
    }

    @Test
    void retry_pgApproved_completesRetryRefundAndClosesTrade() {
        Trade trade = tradeAtRefunding();
        Refund failed = failedRefundFor(trade);
        when(refunds.findById(failed.id())).thenReturn(Optional.of(failed));
        when(trades.findById(trade.id())).thenReturn(Optional.of(trade));
        when(pgClient.refund(any())).thenReturn(PgClient.RefundResult.approved("pg-refund-retry-1"));

        service.retry(failed.id());

        // 새로 만들어진 retry Refund 가 COMPLETED 로 저장된다 (원래 failed 와 별개 row).
        Refund savedRetry = lastSavedRefund();
        assertThat(savedRetry.id()).isNotEqualTo(failed.id());
        assertThat(savedRetry.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(savedRetry.pgRefundId()).isEqualTo("pg-refund-retry-1");
        assertThat(trade.status()).isEqualTo(TradeStatus.FAILED);
    }

    @Test
    void retry_pgRejectedAgain_throwsAndMarksRetryRefundFailed() {
        Trade trade = tradeAtRefunding();
        Refund failed = failedRefundFor(trade);
        when(refunds.findById(failed.id())).thenReturn(Optional.of(failed));
        when(trades.findById(trade.id())).thenReturn(Optional.of(trade));
        when(pgClient.refund(any())).thenReturn(PgClient.RefundResult.rejected("pg still down"));

        assertThatThrownBy(() -> service.retry(failed.id()))
                .isInstanceOf(PgFailureException.class);

        // 새 retry Refund 가 FAILED 로 남고, Trade 는 REFUNDING 에 머무른다.
        assertThat(lastSavedRefund().status()).isEqualTo(RefundStatus.FAILED);
        assertThat(trade.status()).isEqualTo(TradeStatus.REFUNDING);
    }

    @Test
    void retry_rejectsNonFailedRefund() {
        Trade trade = tradeAtRefunding();
        // REQUESTED 상태의 Refund — 재시도 대상이 아니다.
        Refund requested = Refund.request(trade.id(), BUYER,
                trade.feeSnapshot().buyerCharge(), "fake item", NOW);
        when(refunds.findById(requested.id())).thenReturn(Optional.of(requested));

        assertThatThrownBy(() -> service.retry(requested.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void retry_unknownRefund_throwsRefundNotFound() {
        RefundId missing = RefundId.newId();
        when(refunds.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retry(missing))
                .isInstanceOf(RefundNotFoundException.class);
    }

    /**
     * 회귀 방지 — 1차 재시도가 PG 거절을 받은 뒤 2차 재시도가 PG 를 *다시* 호출해야 한다.
     *
     * <p>compensation_log businessKey 가 원래 refundId 라면 1차 재시도가 남긴 FAILED row 때문에
     * 2차 재시도는 PG 호출 없이 캐시된 실패만 반환한다 — 운영자 재시도 endpoint 가 1회용이
     * 되어버린다. businessKey 가 "이번 retry Refund 의 id" 이면 매 재시도가 자기 row 를 갖고
     * PG 를 실제로 다시 호출한다.</p>
     */
    @Test
    void secondRetry_afterFirstRetryFailed_callsPgAgain() {
        Trade trade = tradeAtRefunding();
        Refund failed = failedRefundFor(trade);
        when(refunds.findById(failed.id())).thenReturn(Optional.of(failed));
        when(trades.findById(trade.id())).thenReturn(Optional.of(trade));

        // 1차 재시도 — PG 거절.
        when(pgClient.refund(any())).thenReturn(PgClient.RefundResult.rejected("pg down"));
        assertThatThrownBy(() -> service.retry(failed.id()))
                .isInstanceOf(PgFailureException.class);

        // 2차 재시도 — 이번엔 PG 가 살아남. 캐시된 실패가 아니라 실제 호출이 일어나야 한다.
        when(pgClient.refund(any())).thenReturn(PgClient.RefundResult.approved("pg-refund-retry-2"));
        service.retry(failed.id());

        // PG 가 2번 호출됨 (1차 실패 + 2차 성공) — businessKey 가 재시도마다 달라야만 성립.
        verify(pgClient, times(2)).refund(any());
        assertThat(trade.status()).isEqualTo(TradeStatus.FAILED);
        assertThat(lastSavedRefund().status()).isEqualTo(RefundStatus.COMPLETED);
    }

    /** INSPECTION_FAILED → REFUNDING 까지 끌어올린 Trade — RetryRefund 의 진입 전제. */
    private Trade tradeAtRefunding() {
        Listing ask = Listing.place(SKU, SELLER, money(140_000), NOW);
        Bid bid = Bid.place(SKU, BUYER, money(150_000), NOW);
        Trade t = Trade.match(ask, bid, money(150_000), POLICY, NOW);
        t.authorizePayment("pg-payment-1", NOW);
        t.startSellerShipping(NOW);
        t.arriveAtInspection(NOW);
        t.failInspection("fake item", NOW);
        t.startRefunding(NOW);
        return t;
    }

    /** 첫 환불이 PG 거절로 FAILED 가 된 Refund — 운영자가 재시도하려는 대상. */
    private Refund failedRefundFor(Trade trade) {
        Refund r = Refund.request(trade.id(), BUYER,
                trade.feeSnapshot().buyerCharge(), "fake item", NOW);
        r.fail("pg unavailable", NOW);
        return r;
    }

    /** refunds.save 로 마지막에 저장된 Refund (= 새로 만들어진 retry Refund). */
    private Refund lastSavedRefund() {
        ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
        verify(refunds, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private static Money money(long won) {
        return Money.of(BigDecimal.valueOf(won), KRW);
    }

    /** 테스트 전용 in-memory store — RefundBuyerServiceTest 와 동일 구현. */
    static final class InMemoryCompensationLogStore implements CompensationLogStore {
        private final java.util.Map<String, Entry> rows = new java.util.concurrent.ConcurrentHashMap<>();

        private static String k(String op, String key) { return op + "|" + key; }

        @Override
        public void begin(String operation, String businessKey, Instant now) {
            var prev = rows.putIfAbsent(k(operation, businessKey),
                    new Entry(operation, businessKey, Status.IN_PROGRESS, null, null, null, now, null));
            if (prev != null) throw new DuplicateBeginException(operation, businessKey);
        }

        @Override
        public void complete(String operation, String businessKey, String code, String msg, String externalId, Instant now) {
            rows.put(k(operation, businessKey),
                    new Entry(operation, businessKey, Status.COMPLETED, code, msg, externalId,
                            rows.get(k(operation, businessKey)).startedAt(), now));
        }

        @Override
        public void fail(String operation, String businessKey, String code, String msg, Instant now) {
            rows.put(k(operation, businessKey),
                    new Entry(operation, businessKey, Status.FAILED, code, msg, null,
                            rows.get(k(operation, businessKey)).startedAt(), now));
        }

        @Override
        public Optional<Entry> find(String operation, String businessKey) {
            return Optional.ofNullable(rows.get(k(operation, businessKey)));
        }
    }
}
