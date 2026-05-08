package com.example.market.adapter.out.pg;

import com.example.market.application.port.out.PgClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 운영용 PG (결제 게이트웨이) 클라이언트. {@code market.pg.enabled=true} 일 때 활성.
 *
 * <p>Resilience4j 의 {@code @CircuitBreaker} (외부 호출 실패율이 임계치를 넘으면 호출 자체를
 * 차단해 자기 시스템을 보호하는 회로 차단기) + {@code @Retry} 적용 (인스턴스 이름 = "pg").
 * 차단 (OPEN) 상태에서는 fallback 메서드로 즉시 거절을 반환하고, 이를 받은 AuthorizePaymentService
 * 가 Trade.cancelOnPaymentFailure 를 호출한다.</p>
 *
 * <p>HTTP 호출은 Spring 6.1 이상의 RestClient 를 사용한다 (가상 스레드와 잘 맞고, OpenFeign
 * 보다 최신).</p>
 */
@Component("rawPgClient")
@ConditionalOnProperty(name = "market.pg.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class RestPgClient implements PgClient {

    private final RestClient pgRestClient;

    @Override
    @CircuitBreaker(name = "pg", fallbackMethod = "authorizeFallback")
    @Retry(name = "pg")
    public AuthorizeResult authorize(AuthorizeRequest req) {
        return pgRestClient.post()
                .uri("/v1/payments/authorize")
                .body(req)
                .retrieve()
                .body(AuthorizeResult.class);
    }

    @SuppressWarnings("unused")
    private AuthorizeResult authorizeFallback(AuthorizeRequest req, Throwable t) {
        log.warn("[pg] authorize fallback — {}: {}", t.getClass().getSimpleName(), t.getMessage());
        return AuthorizeResult.rejected("CB_OPEN", "PG unavailable: " + t.getMessage());
    }

    @Override
    @CircuitBreaker(name = "pg", fallbackMethod = "refundFallback")
    @Retry(name = "pg")
    public RefundResult refund(RefundRequest req) {
        return pgRestClient.post()
                .uri("/v1/payments/refund")
                .body(req)
                .retrieve()
                .body(RefundResult.class);
    }

    @SuppressWarnings("unused")
    private RefundResult refundFallback(RefundRequest req, Throwable t) {
        log.warn("[pg] refund fallback — {}: {}", t.getClass().getSimpleName(), t.getMessage());
        return RefundResult.rejected("PG unavailable: " + t.getMessage());
    }
}
