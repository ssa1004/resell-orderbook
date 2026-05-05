package com.example.market.adapter.out.pg;

import com.example.market.application.port.out.PgClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 로컬 dev 용 Mock PG. {@code market.pg.enabled=false} 일 때 활성. 항상 승인.
 *
 * <p>실패 시뮬: idempotencyKey 가 "FAIL_" 로 시작하면 reject.</p>
 */
@Component
@ConditionalOnProperty(name = "market.pg.enabled", havingValue = "false", matchIfMissing = true)
@Slf4j
public class MockPgClient implements PgClient {

    @Override
    public AuthorizeResult authorize(AuthorizeRequest req) {
        if (req.idempotencyKey().startsWith("FAIL_")) {
            log.info("[mock-pg] simulating authorize failure for {}", req.idempotencyKey());
            return AuthorizeResult.rejected("MOCK_FAIL", "simulated authorize failure");
        }
        String pgPaymentId = "mock-pg-" + UUID.randomUUID();
        log.info("[mock-pg] authorized {} → {}", req.idempotencyKey(), pgPaymentId);
        return AuthorizeResult.approved(pgPaymentId);
    }

    @Override
    public RefundResult refund(RefundRequest req) {
        if (req.pgPaymentId() != null && req.pgPaymentId().startsWith("FAIL_")) {
            return RefundResult.rejected("simulated refund failure");
        }
        String pgRefundId = "mock-refund-" + UUID.randomUUID();
        log.info("[mock-pg] refunded {} → {}", req.pgPaymentId(), pgRefundId);
        return RefundResult.approved(pgRefundId);
    }
}
