package com.example.market.application.service

import com.example.market.application.exception.TradeNotFoundException
import com.example.market.application.port.`in`.SettleTradeUseCase
import com.example.market.application.port.out.BankTransferClient
import com.example.market.application.port.out.EventPublisher
import com.example.market.application.port.out.PayoutRepository
import com.example.market.application.port.out.TradeRepository
import com.example.market.domain.settlement.Payout
import com.example.market.domain.trading.TradeId
import com.example.market.domain.trading.TradeStatus
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * TradeCompleted 컨슈머 → Payout.schedule(snapshot) → bankTransfer.send → Payout.send.
 *
 * Idempotency 이중 보호 (ADR-0023):
 * 1. 1차 — 같은 Trade 에 Payout 이 이미 있으면 기존 반환 (DB UNIQUE).
 * 2. 2차 — [CompensationGuard] 로 은행 송금 호출이 *정확히 한 번* 일어나게. 응답 유실
 *    시나리오에서 같은 trade 에 송금이 두 번 일어나지 않게 한다.
 */
@Service
open class SettleTradeService(
    private val trades: TradeRepository,
    private val payouts: PayoutRepository,
    private val bank: BankTransferClient,
    private val events: EventPublisher,
    private val compensationGuard: CompensationGuard,
    private val clock: Clock,
) : SettleTradeUseCase {

    @Transactional
    override fun settle(tradeId: TradeId): Payout {
        val existing = payouts.findByTradeId(tradeId)
        if (existing.isPresent) {
            log.info("settle idempotent — trade {} already has payout {}", tradeId, existing.get().id)
            return existing.get()
        }
        val trade = trades.findById(tradeId)
            .orElseThrow { TradeNotFoundException(tradeId) }
        check(trade.status == TradeStatus.COMPLETED) {
            "settle requires COMPLETED, was ${trade.status}"
        }

        val now = clock.instant()
        val payout = Payout.schedule(trade.id, trade.sellerId, trade.feeSnapshot, now)
        payouts.save(payout)

        val outcome = compensationGuard.runOnce<BankTransferClient.SendResult>(OP, trade.id.toString()) { _ ->
            val sendResult = bank.send(
                BankTransferClient.SendRequest(
                    payout.id.toString(), trade.sellerId, payout.netAmount,
                    "RESELL settlement ${trade.id}",
                ),
            )
            if (sendResult.accepted) {
                CompensationGuard.Outcome.completed(
                    sendResult.bankTransferId, "ACCEPTED", "ok", sendResult,
                )
            } else {
                CompensationGuard.Outcome.failed(
                    "REJECTED", sendResult.errorMessage, sendResult,
                )
            }
        }

        if (outcome.completed) {
            val ev = payout.send(outcome.externalId!!, now)
            payouts.save(payout)
            events.publish(ev)
            log.info(
                "payout sent trade={} payout={} amount={}",
                trade.id, payout.id,
                SensitiveLogging.maskAmount(payout.netAmount.amount),
            )
        } else {
            val failEv = payout.fail(outcome.responseMessage ?: "unknown", now)
            payouts.save(payout)
            events.publish(failEv)
            log.warn("payout send failed trade={} reason={}", trade.id, outcome.responseMessage)
        }
        return payout
    }

    companion object {
        private val log = LoggerFactory.getLogger(SettleTradeService::class.java)
        private const val OP = "SETTLE_PAYOUT"
    }
}
