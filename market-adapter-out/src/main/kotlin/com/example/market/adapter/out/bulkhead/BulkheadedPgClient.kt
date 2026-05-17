package com.example.market.adapter.out.bulkhead

import com.example.market.application.port.out.PgClient
import org.slf4j.LoggerFactory

/**
 * [PgClient] 를 [ExternalCallBulkhead] 로 감싼 데코레이터. PG (결제 게이트웨이) 가
 * 느려져도 servlet thread 가 그대로 점유되지 않고, 큐 포화 시 즉시 거절 결과로 fallback.
 *
 * ### fallback 정책
 *
 * 풀 포화 ([ExternalCallBulkhead.BulkheadCapacityExceededException]) 와 await 타임아웃
 * ([ExternalCallBulkhead.BulkheadAwaitTimeoutException]) 두 경우 모두 도메인 결과 객체
 * (rejected) 로 변환해서 반환한다 — 호출자는 이미 PG 거절을 받는 표준 흐름을 가지고 있어 일관된
 * 처리가 가능 (예: `AuthorizePaymentService` 가 `Trade.cancelOnPaymentFailure` 호출).
 *
 * 다른 예외는 그대로 전파 — Resilience4j 의 `@CircuitBreaker` fallback 이 안쪽에서 이미
 * 처리하기 때문에 여기까지 올라오면 정말 *예상 못한* 흐름.
 */
class BulkheadedPgClient(
    private val delegate: PgClient,
    private val bulkhead: ExternalCallBulkhead,
) : PgClient {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun authorize(req: PgClient.AuthorizeRequest): PgClient.AuthorizeResult {
        return try {
            bulkhead.execute { delegate.authorize(req) }
        } catch (e: ExternalCallBulkhead.BulkheadCapacityExceededException) {
            log.warn("[pg] bulkhead 포화 — authorize 거절 fallback (pool={})", e.poolName)
            PgClient.AuthorizeResult.rejected("BULKHEAD_FULL", "결제 시스템 부하 — 잠시 후 재시도")
        } catch (e: ExternalCallBulkhead.BulkheadAwaitTimeoutException) {
            log.warn("[pg] bulkhead await timeout — authorize 거절 fallback (pool={})", e.poolName)
            PgClient.AuthorizeResult.rejected("BULKHEAD_TIMEOUT", "결제 응답 지연 — 잠시 후 재시도")
        }
    }

    override fun refund(req: PgClient.RefundRequest): PgClient.RefundResult {
        return try {
            bulkhead.execute { delegate.refund(req) }
        } catch (e: ExternalCallBulkhead.BulkheadCapacityExceededException) {
            log.warn("[pg] bulkhead 포화 — refund 거절 fallback (pool={})", e.poolName)
            PgClient.RefundResult.rejected("BULKHEAD_FULL: 결제 시스템 부하")
        } catch (e: ExternalCallBulkhead.BulkheadAwaitTimeoutException) {
            log.warn("[pg] bulkhead await timeout — refund 거절 fallback (pool={})", e.poolName)
            PgClient.RefundResult.rejected("BULKHEAD_TIMEOUT: 결제 응답 지연")
        }
    }
}
