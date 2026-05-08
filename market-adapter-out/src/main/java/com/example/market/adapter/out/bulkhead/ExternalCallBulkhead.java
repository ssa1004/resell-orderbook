package com.example.market.adapter.out.bulkhead;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 외부 호출 1개를 위한 격리 풀 — 도착하는 호출을 *전용 ThreadPool* 에서 실행해서 servlet
 * thread (= 사용자 요청을 받아 처리하는 메인 톰캣 워커) 가 외부 시스템 지연에 점유되지 않게
 * 한다 (선박의 격벽 = bulkhead 비유).
 *
 * <h3>왜 thread-pool bulkhead 인가</h3>
 *
 * <p>Resilience4j 는 두 종류 — 단순 semaphore (= 토큰 카운터) bulkhead 와 thread-pool bulkhead.
 * <b>외부 HTTP/DB 호출처럼 진짜로 시간이 걸리는 작업은 thread-pool</b> 이 적합. semaphore 는
 * 호출자 thread (servlet thread) 가 그대로 외부 응답을 기다려야 해서 격리 효과가 절반.
 * thread-pool 은 별 풀이 응답을 받고, servlet thread 는 future 만 짧게 await — 풀 포화 시점에
 * 즉시 빠져나올 수 있다.</p>
 *
 * <h3>동작</h3>
 *
 * <ol>
 *   <li>{@code execute(supplier)} 가 작업을 풀에 제출. 풀이 한가하면 즉시 실행, 코어 다 차 있으면
 *       큐 (capacity 만큼) 에 들어감.</li>
 *   <li>큐도 차 있으면 Resilience4j 가 {@link BulkheadFullException} 을 던진다 — 호출자는 즉시
 *       fallback (예: 503 + Retry-After) 으로 빠질 수 있다.</li>
 *   <li>제출 후 {@code awaitTimeout} 안에 결과가 안 오면 {@link BulkheadAwaitTimeoutException}
 *       으로 교체 — 사용자가 무한정 기다리지 않게.</li>
 * </ol>
 *
 * <h3>fallback / 예외 처리</h3>
 *
 * <p>본 클래스는 *예외를 그대로* 던진다. 호출자가 도메인 의미 (예: "PG 거절") 로 해석할 책임을
 * 가진다. {@link BulkheadFullException} 만 명시적으로 잡아 돌릴 수도 있게 (예: PG 의 경우
 * Resilience4j 의 CircuitBreaker fallback 처럼 거절 결과로 변환).</p>
 *
 * <h3>Resilience4j 의 다른 데코레이터와의 결합 순서</h3>
 *
 * <p>표준 권장 순서: <b>Bulkhead → CircuitBreaker → Retry</b> (외부 → 내부). 즉 가장 바깥은
 * 코어 보호 (bulkhead), 그 다음이 회로 (CB), 가장 안이 재시도. 본 시스템은 PG 어댑터에서
 * {@code @CircuitBreaker + @Retry} 가 이미 메서드 단위로 적용 — Bulkhead 를 *데코레이터로
 * 외부에서 wrap* 하면 자연스럽게 같은 순서가 된다.</p>
 */
public final class ExternalCallBulkhead {

    private final ThreadPoolBulkhead bulkhead;
    private final Duration awaitTimeout;

    /** 큐가 꽉 찼을 때 외부에 던지는 예외. {@link BulkheadFullException} 을 도메인 메시지로 wrap. */
    public static final class BulkheadCapacityExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String poolName;
        private final long retryAfterSeconds;

        public BulkheadCapacityExceededException(String poolName, long retryAfterSeconds, Throwable cause) {
            super("bulkhead 'pool=" + poolName + "' is full — try again later", cause);
            this.poolName = poolName;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public String poolName() { return poolName; }
        public long retryAfterSeconds() { return retryAfterSeconds; }
    }

    /** await 시간 초과 — 풀에는 들어갔으나 정해진 시간 안에 작업이 끝나지 않음. */
    public static final class BulkheadAwaitTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String poolName;

        public BulkheadAwaitTimeoutException(String poolName, Duration awaitTimeout, Throwable cause) {
            super("bulkhead 'pool=" + poolName + "' await timeout after " + awaitTimeout, cause);
            this.poolName = poolName;
        }

        public String poolName() { return poolName; }
    }

    private final long retryAfterSeconds;

    public ExternalCallBulkhead(ThreadPoolBulkhead bulkhead,
                                Duration awaitTimeout,
                                long retryAfterSeconds) {
        this.bulkhead = Objects.requireNonNull(bulkhead);
        this.awaitTimeout = Objects.requireNonNull(awaitTimeout);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * 작업을 격리 풀에서 실행. 풀 포화 시 {@link BulkheadCapacityExceededException}, 시간 초과 시
     * {@link BulkheadAwaitTimeoutException}, 작업 자체의 예외는 그대로 throw.
     */
    public <T> T execute(Supplier<T> work) {
        try {
            return bulkhead.executeSupplier(work)
                    .toCompletableFuture()
                    .get(awaitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (BulkheadFullException e) {
            throw new BulkheadCapacityExceededException(bulkhead.getName(), retryAfterSeconds, e);
        } catch (TimeoutException e) {
            throw new BulkheadAwaitTimeoutException(bulkhead.getName(), awaitTimeout, e);
        } catch (ExecutionException | CompletionException e) {
            // 작업 안에서 던진 예외는 cause 로 unwrap 해서 그대로 전파.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof BulkheadFullException bfe) {
                throw new BulkheadCapacityExceededException(bulkhead.getName(), retryAfterSeconds, bfe);
            }
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("bulkhead execution interrupted", e);
        }
    }

    public String name() { return bulkhead.getName(); }

    /**
     * factory — 이름 + 설정으로 instance 생성. registry 에 등록되어 metric 수집됨.
     */
    public static ExternalCallBulkhead create(ThreadPoolBulkheadRegistry registry,
                                              String poolName,
                                              BulkheadProperties.Instance cfg) {
        ThreadPoolBulkheadConfig config = ThreadPoolBulkheadConfig.custom()
                .coreThreadPoolSize(cfg.getCoreSize())
                .maxThreadPoolSize(Math.max(cfg.getMaxPoolSize(), cfg.getCoreSize()))
                .queueCapacity(cfg.getQueueCapacity())
                .keepAliveDuration(Duration.ofSeconds(60))
                .build();
        ThreadPoolBulkhead bulkhead = registry.bulkhead(poolName, config);
        return new ExternalCallBulkhead(bulkhead, cfg.getAwaitTimeout(), cfg.getRetryAfterSeconds());
    }
}
