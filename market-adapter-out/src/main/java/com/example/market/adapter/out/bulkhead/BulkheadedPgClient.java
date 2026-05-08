package com.example.market.adapter.out.bulkhead;

import com.example.market.application.port.out.PgClient;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link PgClient} 를 {@link ExternalCallBulkhead} 로 감싼 데코레이터. PG (결제 게이트웨이) 가
 * 느려져도 servlet thread 가 그대로 점유되지 않고, 큐 포화 시 즉시 거절 결과로 fallback.
 *
 * <h3>fallback 정책</h3>
 *
 * <p>풀 포화 ({@link ExternalCallBulkhead.BulkheadCapacityExceededException}) 와 await 타임아웃
 * ({@link ExternalCallBulkhead.BulkheadAwaitTimeoutException}) 두 경우 모두 도메인 결과 객체
 * (rejected) 로 변환해서 반환한다 — 호출자는 이미 PG 거절을 받는 표준 흐름을 가지고 있어 일관된
 * 처리가 가능 (예: {@code AuthorizePaymentService} 가 {@code Trade.cancelOnPaymentFailure} 호출).</p>
 *
 * <p>다른 예외는 그대로 전파 — Resilience4j 의 {@code @CircuitBreaker} fallback 이 안쪽에서 이미
 * 처리하기 때문에 여기까지 올라오면 정말 *예상 못한* 흐름.</p>
 */
@Slf4j
public class BulkheadedPgClient implements PgClient {

    private final PgClient delegate;
    private final ExternalCallBulkhead bulkhead;

    public BulkheadedPgClient(PgClient delegate, ExternalCallBulkhead bulkhead) {
        this.delegate = delegate;
        this.bulkhead = bulkhead;
    }

    @Override
    public AuthorizeResult authorize(AuthorizeRequest req) {
        try {
            return bulkhead.execute(() -> delegate.authorize(req));
        } catch (ExternalCallBulkhead.BulkheadCapacityExceededException e) {
            log.warn("[pg] bulkhead 포화 — authorize 거절 fallback (pool={})", e.poolName());
            return AuthorizeResult.rejected("BULKHEAD_FULL",
                    "결제 시스템 부하 — 잠시 후 재시도");
        } catch (ExternalCallBulkhead.BulkheadAwaitTimeoutException e) {
            log.warn("[pg] bulkhead await timeout — authorize 거절 fallback (pool={})", e.poolName());
            return AuthorizeResult.rejected("BULKHEAD_TIMEOUT",
                    "결제 응답 지연 — 잠시 후 재시도");
        }
    }

    @Override
    public RefundResult refund(RefundRequest req) {
        try {
            return bulkhead.execute(() -> delegate.refund(req));
        } catch (ExternalCallBulkhead.BulkheadCapacityExceededException e) {
            log.warn("[pg] bulkhead 포화 — refund 거절 fallback (pool={})", e.poolName());
            return RefundResult.rejected("BULKHEAD_FULL: 결제 시스템 부하");
        } catch (ExternalCallBulkhead.BulkheadAwaitTimeoutException e) {
            log.warn("[pg] bulkhead await timeout — refund 거절 fallback (pool={})", e.poolName());
            return RefundResult.rejected("BULKHEAD_TIMEOUT: 결제 응답 지연");
        }
    }
}
