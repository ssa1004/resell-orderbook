package com.example.market.adapter.out.bulkhead;

import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ExternalCallBulkhead} 의 격리 거동 검증.
 *
 * <ul>
 *   <li>풀 코어 안에서는 정상 실행</li>
 *   <li>코어 + 큐 모두 차면 즉시 {@link ExternalCallBulkhead.BulkheadCapacityExceededException}</li>
 *   <li>실행 중 throw 한 도메인 예외는 그대로 전파</li>
 *   <li>한 풀 (pg) 의 포화가 다른 풀 (bank) 에는 영향 없음</li>
 * </ul>
 */
class ExternalCallBulkheadTest {

    private final ThreadPoolBulkheadRegistry registry = ThreadPoolBulkheadRegistry.ofDefaults();

    @Test
    void successfulExecution_returnsResult() {
        ExternalCallBulkhead bh = newBulkhead("ok-pool", 2, 4, 500);
        AtomicInteger counter = new AtomicInteger();

        Integer result = bh.execute(counter::incrementAndGet);

        assertThat(result).isEqualTo(1);
    }

    @Test
    void poolFull_throwsBulkheadCapacityExceeded() throws Exception {
        // core=1, queue=1 → 동시 처리 가능한 작업 = 2개. 3번째는 거절.
        ExternalCallBulkhead bh = newBulkhead("tiny-pool", 1, 1, 200);
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(2);

        ExecutorService submitter = Executors.newFixedThreadPool(3);
        try {
            // 작업 2개를 hang 상태로 채워 풀+큐 점유.
            submitter.submit(() -> bh.execute(() -> { ready.countDown(); awaitUnchecked(hold); return "a"; }));
            submitter.submit(() -> bh.execute(() -> { ready.countDown(); awaitUnchecked(hold); return "b"; }));

            ready.await(2, TimeUnit.SECONDS);
            // 첫 번째 작업이 코어를 잡고 시작했고 두 번째는 큐 / 두 번째 작업도 풀 안에서 시작 가능
            // (max=core 라 큐 하나를 통과해 들어올 수 있지만, 본 테스트는 "어느 쪽이든 3번째는 거절"
            // 이 핵심).
            // 짧게 기다려 풀이 완전히 점유되도록.
            Thread.sleep(50);

            // 3번째 호출은 큐도 만석이라 BulkheadFullException → BulkheadCapacityExceededException.
            assertThatThrownBy(() -> bh.execute(() -> "c"))
                    .isInstanceOf(ExternalCallBulkhead.BulkheadCapacityExceededException.class)
                    .satisfies(e -> {
                        var ex = (ExternalCallBulkhead.BulkheadCapacityExceededException) e;
                        assertThat(ex.poolName()).isEqualTo("tiny-pool");
                        assertThat(ex.retryAfterSeconds()).isPositive();
                    });
        } finally {
            hold.countDown();
            submitter.shutdownNow();
        }
    }

    @Test
    void executionException_isUnwrappedAndPropagated() {
        ExternalCallBulkhead bh = newBulkhead("err-pool", 1, 1, 500);

        assertThatThrownBy(() -> bh.execute(() -> {
            throw new IllegalStateException("도메인 실패");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("도메인 실패");
    }

    @Test
    void differentPools_areIndependent() throws Exception {
        // pg 풀이 포화되어도 bank 풀에서는 정상 처리.
        ExternalCallBulkhead pg = newBulkhead("pg", 1, 1, 200);
        ExternalCallBulkhead bank = newBulkhead("bank", 2, 4, 200);

        CountDownLatch holdPg = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            // pg 풀 (core=1, queue=1) 을 2개의 hang 작업으로 채움.
            pool.submit(() -> pg.execute(() -> { awaitUnchecked(holdPg); return null; }));
            pool.submit(() -> pg.execute(() -> { awaitUnchecked(holdPg); return null; }));
            Thread.sleep(50);

            // pg 는 즉시 거절.
            assertThatThrownBy(() -> pg.execute(() -> "x"))
                    .isInstanceOf(ExternalCallBulkhead.BulkheadCapacityExceededException.class);

            // bank 는 정상.
            String r = bank.execute(() -> "ok");
            assertThat(r).isEqualTo("ok");
        } finally {
            holdPg.countDown();
            pool.shutdownNow();
        }
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

    private static void awaitUnchecked(CountDownLatch l) {
        try { l.await(5, TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
