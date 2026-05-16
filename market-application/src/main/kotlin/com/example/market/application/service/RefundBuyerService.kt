package com.example.market.application.service

import com.example.market.application.exception.PgFailureException
import com.example.market.application.exception.TradeNotFoundException
import com.example.market.application.port.`in`.RefundBuyerUseCase
import com.example.market.application.port.out.EventPublisher
import com.example.market.application.port.out.PgClient
import com.example.market.application.port.out.RefundRepository
import com.example.market.application.port.out.TradeRepository
import com.example.market.domain.settlement.Refund
import com.example.market.domain.trading.TradeId
import com.example.market.domain.trading.TradeStatus
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * InspectionFailed 컨슈머 — Trade.startRefunding → PG.refund() → Refund.complete →
 * Trade.closeAsFailedAfterRefund.
 *
 * idempotent (ADR-0023): [CompensationGuard] 로 PG.refund 가 *정확히 한 번* 일어나도록
 * 보장. 메시지 컨슈머의 at-least-once 중복이 들어와도 PG 가 두 번 호출되지 않는다.
 *
 * PG.refund 실패 시 Refund.fail() — 운영자가 RetryRefundUseCase 로 재시도.
 */
@Service
open class RefundBuyerService(
    private val trades: TradeRepository,
    private val refunds: RefundRepository,
    private val pgClient: PgClient,
    private val events: EventPublisher,
    private val compensationGuard: CompensationGuard,
    private val clock: Clock,
) : RefundBuyerUseCase {

    @Transactional
    override fun refund(tradeId: TradeId): Refund {
        val trade = trades.findById(tradeId)
            .orElseThrow { TradeNotFoundException(tradeId) }

        // idempotent — 이미 REFUNDING/FAILED 라면 기존 Refund 반환
        val existing = refunds.findByTradeId(tradeId)
        if (existing.isPresent) {
            log.info("refund idempotent — trade {} already has refund {}", tradeId, existing.get().id)
            return existing.get()
        }
        check(trade.status == TradeStatus.INSPECTION_FAILED) {
            "refund requires INSPECTION_FAILED, was ${trade.status}"
        }

        val now = clock.instant()
        val startEv = trade.startRefunding(now)
        val refund = Refund.request(
            trade.id, trade.buyerId,
            trade.feeSnapshot.buyerCharge,  // 검수비/배송비 포함 전액
            trade.inspectionFailReason, now,
        )
        refunds.save(refund)
        trades.save(trade)
        events.publish(startEv)

        // PG 환불 호출 — CompensationGuard 가 정확히 한 번 보장. 같은 trade 의 refund 가 재호출
        // 되어도 PG 가 두 번 호출되지 않고 캐시된 결과 (pgRefundId) 가 반환된다.
        val outcome = compensationGuard.runOnce<PgClient.RefundResult>(OP, trade.id.toString()) { _ ->
            val result = pgClient.refund(
                PgClient.RefundRequest(trade.pgPaymentId!!, refund.amount, refund.reason),
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
            val doneEv = refund.complete(outcome.externalId!!, now)
            val closeEv = trade.closeAsFailedAfterRefund(now)
            refunds.save(refund)
            trades.save(trade)
            events.publishAll(doneEv, closeEv)
            log.info("refund complete trade={} pgRefundId={}", trade.id, outcome.externalId)
        } else {
            val failEv = refund.fail(outcome.responseMessage ?: "unknown", now)
            refunds.save(refund)
            events.publish(failEv)
            log.warn("refund failed trade={} reason={}", trade.id, outcome.responseMessage)
            // PG 실패 — Trade 는 REFUNDING 에 머무름. 운영자 RetryRefundUseCase 로 처리
            throw PgFailureException("REFUND_REJECTED", outcome.responseMessage ?: "unknown")
        }
        return refund
    }

    companion object {
        private val log = LoggerFactory.getLogger(RefundBuyerService::class.java)
        private const val OP = "REFUND"
    }
}
