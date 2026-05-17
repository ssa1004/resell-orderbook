package com.example.market.adapter.out.pg

import com.example.market.application.port.out.PgClient
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * 운영용 PG (결제 게이트웨이) 클라이언트. `market.pg.enabled=true` 일 때 활성.
 *
 * Resilience4j 의 `@CircuitBreaker` (외부 호출 실패율이 임계치를 넘으면 호출 자체를
 * 차단해 자기 시스템을 보호하는 회로 차단기) + `@Retry` 적용 (인스턴스 이름 = "pg").
 * 차단 (OPEN) 상태에서는 fallback 메서드로 즉시 거절을 반환하고, 이를 받은 AuthorizePaymentService
 * 가 Trade.cancelOnPaymentFailure 를 호출한다.
 *
 * HTTP 호출은 Spring 6.1 이상의 RestClient 를 사용한다 (가상 스레드와 잘 맞고, OpenFeign
 * 보다 최신).
 */
@Component("rawPgClient")
@ConditionalOnProperty(name = ["market.pg.enabled"], havingValue = "true")
open class RestPgClient(
    private val pgRestClient: RestClient,
) : PgClient {

    private val log = LoggerFactory.getLogger(javaClass)

    @CircuitBreaker(name = "pg", fallbackMethod = "authorizeFallback")
    @Retry(name = "pg")
    override fun authorize(req: PgClient.AuthorizeRequest): PgClient.AuthorizeResult =
        pgRestClient.post()
            .uri("/v1/payments/authorize")
            .body(req)
            .retrieve()
            .body(PgClient.AuthorizeResult::class.java)!!

    @Suppress("unused")
    private fun authorizeFallback(req: PgClient.AuthorizeRequest, t: Throwable): PgClient.AuthorizeResult {
        log.warn("[pg] authorize fallback — {}: {}", t.javaClass.simpleName, t.message)
        return PgClient.AuthorizeResult.rejected("CB_OPEN", "PG unavailable: ${t.message}")
    }

    @CircuitBreaker(name = "pg", fallbackMethod = "refundFallback")
    @Retry(name = "pg")
    override fun refund(req: PgClient.RefundRequest): PgClient.RefundResult =
        pgRestClient.post()
            .uri("/v1/payments/refund")
            .body(req)
            .retrieve()
            .body(PgClient.RefundResult::class.java)!!

    @Suppress("unused")
    private fun refundFallback(req: PgClient.RefundRequest, t: Throwable): PgClient.RefundResult {
        log.warn("[pg] refund fallback — {}: {}", t.javaClass.simpleName, t.message)
        return PgClient.RefundResult.rejected("PG unavailable: ${t.message}")
    }
}
