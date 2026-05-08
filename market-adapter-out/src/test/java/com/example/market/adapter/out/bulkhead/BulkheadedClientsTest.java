package com.example.market.adapter.out.bulkhead;

import com.example.market.application.port.out.BankTransferClient;
import com.example.market.application.port.out.PgClient;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BulkheadedPgClient} / {@link BulkheadedBankTransferClient} 의 fallback 거동.
 *
 * <ul>
 *   <li>delegate 가 정상 응답 → 그대로 통과</li>
 *   <li>풀 포화 → AuthorizeResult / RefundResult / SendResult 의 rejected 결과로 fallback</li>
 *   <li>delegate 가 예외 throw → 도메인 거절 결과가 아니라 예외 그대로 전파 (CircuitBreaker
 *       fallback 이 안쪽에서 처리)</li>
 * </ul>
 */
class BulkheadedClientsTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private final ThreadPoolBulkheadRegistry registry = ThreadPoolBulkheadRegistry.ofDefaults();

    @Test
    void pgClient_passThrough_whenDelegateApproves() {
        PgClient delegate = new PgClient() {
            @Override
            public AuthorizeResult authorize(AuthorizeRequest req) {
                return AuthorizeResult.approved("pg-1");
            }
            @Override
            public RefundResult refund(RefundRequest req) {
                return RefundResult.approved("refund-1");
            }
        };
        BulkheadedPgClient client = new BulkheadedPgClient(delegate, newBulkhead("pg-pass"));

        var ar = client.authorize(new PgClient.AuthorizeRequest(
                "k", money(10_000), "trade-1", "buyer"));
        assertThat(ar.approved()).isTrue();
        assertThat(ar.pgPaymentId()).isEqualTo("pg-1");

        var rr = client.refund(new PgClient.RefundRequest("pg-1", money(5_000), "reason"));
        assertThat(rr.approved()).isTrue();
        assertThat(rr.pgRefundId()).isEqualTo("refund-1");
    }

    @Test
    void pgClient_returnsFallback_whenPoolFull() throws Exception {
        // delegate 의 응답을 hang 시켜 풀 포화 유도.
        CountDownLatch hang = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        PgClient delegate = new PgClient() {
            @Override
            public AuthorizeResult authorize(AuthorizeRequest req) {
                calls.incrementAndGet();
                awaitUnchecked(hang);
                return AuthorizeResult.approved("pg-x");
            }
            @Override
            public RefundResult refund(RefundRequest req) {
                awaitUnchecked(hang);
                return RefundResult.approved("rf-x");
            }
        };
        BulkheadedPgClient client = new BulkheadedPgClient(delegate,
                newBulkhead("pg-tiny", 1, 1, 200));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            // 풀 + 큐 채우기 (core=1, queue=1).
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> client.authorize(new PgClient.AuthorizeRequest(
                        "k", money(10_000), "t", "b")));
            }
            Thread.sleep(80);

            // 다음 호출은 BulkheadCapacityExceeded → BULKHEAD_FULL 거절 결과로 fallback.
            var ar = client.authorize(new PgClient.AuthorizeRequest(
                    "k", money(10_000), "t", "b"));
            assertThat(ar.approved()).isFalse();
            assertThat(ar.errorCode()).isEqualTo("BULKHEAD_FULL");
        } finally {
            hang.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void pgClient_propagatesDelegateException() {
        PgClient delegate = new PgClient() {
            @Override
            public AuthorizeResult authorize(AuthorizeRequest req) {
                throw new RuntimeException("PG 5xx");
            }
            @Override
            public RefundResult refund(RefundRequest req) { return RefundResult.approved("x"); }
        };
        BulkheadedPgClient client = new BulkheadedPgClient(delegate, newBulkhead("pg-err"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        client.authorize(new PgClient.AuthorizeRequest(
                                "k", money(10_000), "t", "b")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("PG 5xx");
    }

    @Test
    void bankClient_returnsFallback_whenPoolFull() throws Exception {
        CountDownLatch hang = new CountDownLatch(1);
        BankTransferClient delegate = req -> {
            awaitUnchecked(hang);
            return BankTransferClient.SendResult.accepted("bank-x");
        };
        BulkheadedBankTransferClient client = new BulkheadedBankTransferClient(delegate,
                newBulkhead("bank-tiny", 1, 1, 200));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 2; i++) {
                final int idx = i;
                pool.submit(() -> client.send(new BankTransferClient.SendRequest(
                        "idem-" + idx, UserId.of("seller"), money(50_000), "memo")));
            }
            Thread.sleep(80);

            var sr = client.send(new BankTransferClient.SendRequest(
                    "idem-99", UserId.of("seller"), money(50_000), "memo"));
            assertThat(sr.accepted()).isFalse();
            assertThat(sr.errorMessage()).contains("BULKHEAD_FULL");
        } finally {
            hang.countDown();
            pool.shutdownNow();
        }
    }

    private ExternalCallBulkhead newBulkhead(String name) {
        return newBulkhead(name, 4, 8, 1000);
    }

    private ExternalCallBulkhead newBulkhead(String name, int core, int queue, long awaitMs) {
        BulkheadProperties.Instance cfg = new BulkheadProperties.Instance();
        cfg.setCoreSize(core);
        cfg.setMaxPoolSize(core);
        cfg.setQueueCapacity(queue);
        cfg.setAwaitTimeout(Duration.ofMillis(awaitMs));
        cfg.setRetryAfterSeconds(1);
        return ExternalCallBulkhead.create(registry, name, cfg);
    }

    private static Money money(long won) {
        return Money.of(BigDecimal.valueOf(won), KRW);
    }

    private static void awaitUnchecked(CountDownLatch l) {
        try { l.await(5, TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
