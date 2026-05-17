package com.example.market.adapter.out.pg

import com.example.market.application.port.out.PgClient
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 로컬 dev 용 Mock PG. `market.pg.enabled=false` 일 때 활성. 항상 승인.
 *
 * 실패 시뮬: idempotencyKey 가 "FAIL_" 로 시작하면 reject.
 */
@Component("rawPgClient")
@ConditionalOnProperty(
    name = ["market.pg.enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
open class MockPgClient : PgClient {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun authorize(req: PgClient.AuthorizeRequest): PgClient.AuthorizeResult {
        if (req.idempotencyKey.startsWith("FAIL_")) {
            log.info("[mock-pg] simulating authorize failure for {}", req.idempotencyKey)
            return PgClient.AuthorizeResult.rejected("MOCK_FAIL", "simulated authorize failure")
        }
        val pgPaymentId = "mock-pg-" + UUID.randomUUID()
        log.info("[mock-pg] authorized {} → {}", req.idempotencyKey, pgPaymentId)
        return PgClient.AuthorizeResult.approved(pgPaymentId)
    }

    override fun refund(req: PgClient.RefundRequest): PgClient.RefundResult {
        if (req.pgPaymentId.startsWith("FAIL_")) {
            return PgClient.RefundResult.rejected("simulated refund failure")
        }
        val pgRefundId = "mock-refund-" + UUID.randomUUID()
        log.info("[mock-pg] refunded {} → {}", req.pgPaymentId, pgRefundId)
        return PgClient.RefundResult.approved(pgRefundId)
    }
}
