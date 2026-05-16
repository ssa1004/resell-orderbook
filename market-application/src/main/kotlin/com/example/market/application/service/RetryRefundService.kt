package com.example.market.application.service

import com.example.market.application.exception.PgFailureException
import com.example.market.application.exception.RefundNotFoundException
import com.example.market.application.exception.TradeNotFoundException
import com.example.market.application.port.`in`.RetryRefundUseCase
import com.example.market.application.port.out.EventPublisher
import com.example.market.application.port.out.PgClient
import com.example.market.application.port.out.RefundRepository
import com.example.market.application.port.out.TradeRepository
import com.example.market.domain.settlement.Refund
import com.example.market.domain.settlement.RefundId
import com.example.market.domain.settlement.RefundStatus
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 운영자 admin endpoint — Refund.FAILED 상태의 환불을 PG 재호출.
 *
 * 도메인 Refund 에는 재시도 메서드가 없으므로, 실패한 Refund 를 그대로 두고 새 Refund 를
 * 하나 만들어 PG 를 다시 호출한다 — 실패한 원래 Refund 는 audit log 으로 남는다.
 */
@Service
open class RetryRefundService(
    private val refunds: RefundRepository,
    private val trades: TradeRepository,
    private val pgClient: PgClient,
    private val events: EventPublisher,
    private val compensationGuard: CompensationGuard,
    private val clock: Clock,
) : RetryRefundUseCase {

    @Transactional
    override fun retry(refundId: RefundId) {
        val failedRefund = refunds.findById(refundId)
            .orElseThrow { RefundNotFoundException(refundId) }
        check(failedRefund.status == RefundStatus.FAILED) {
            "retry only allowed for FAILED, was ${failedRefund.status}"
        }
        val trade = trades.findById(failedRefund.tradeId)
            .orElseThrow { TradeNotFoundException(failedRefund.tradeId) }

        val now = clock.instant()
        val retry = Refund.request(
            trade.id, failedRefund.buyerId,
            failedRefund.amount, "RETRY: ${failedRefund.reason}", now,
        )
        refunds.save(retry)

        val outcome = compensationGuard.runOnce<PgClient.RefundResult>(OP, retry.id.toString()) { _ ->
            val result = pgClient.refund(
                PgClient.RefundRequest(trade.pgPaymentId!!, retry.amount, retry.reason),
            )
            if (result.approved) {
                CompensationGuard.Outcome.completed(
                    result.pgRefundId, "APPROVED", "ok", result,
                )
            } else {
                CompensationGuard.Outcome.failed(
                    "REJECTED", result.errorMessage, result,
                )
            }
        }

        if (outcome.completed) {
            val doneEv = retry.complete(outcome.externalId!!, now)
            val closeEv = trade.closeAsFailedAfterRefund(now)
            refunds.save(retry)
            trades.save(trade)
            events.publishAll(doneEv, closeEv)
            log.info("refund retry succeeded refund={} pgRefundId={}", retry.id, outcome.externalId)
        } else {
            val failEv = retry.fail(outcome.responseMessage ?: "unknown", now)
            refunds.save(retry)
            events.publish(failEv)
            log.error("refund retry failed again refund={} reason={}", retry.id, outcome.responseMessage)
            throw PgFailureException("REFUND_RETRY_REJECTED", outcome.responseMessage ?: "unknown")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RetryRefundService::class.java)

        /**
         * RetryRefund 의 compensation_log operation — 원래 REFUND 와 별 row 로 관리.
         *
         * businessKey 는 원래 실패한 refundId 가 아니라 **이번에 새로 만든 retry Refund 의 id**
         * 를 쓴다. [CompensationGuard] 는 같은 (operation, businessKey) 가 FAILED 로 남으면
         * 재호출 시 외부 호출 없이 캐시된 실패를 반환한다 — 원래 refundId 를 키로 쓰면 1차 재시도가
         * PG 거절을 받은 뒤로는 2차 이후 재시도가 PG 를 다시 호출하지 못하고 stale 한 실패만 반환된다
         * (운영자 재시도 endpoint 가 1회용이 되어버림). 재시도 1건마다 별도 Refund row 를 만드는 이
         * 흐름에서는 그 새 row 의 id 가 곧 "이번 보상 시도" 의 고유 키이므로, 이를 businessKey 로
         * 삼으면 매 재시도가 자기 compensation_log row 를 갖고 PG 를 실제로 다시 호출한다. ADR-0023
         * 이 권장한 "FAILED 후 재시도는 새 키로" 정책과도 일치.
         */
        private const val OP = "REFUND_RETRY"
    }
}
