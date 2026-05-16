package com.example.market.application.service

import com.example.market.application.exception.PayoutNotFoundException
import com.example.market.application.port.`in`.MarkPayoutCompletedUseCase
import com.example.market.application.port.out.EventPublisher
import com.example.market.application.port.out.PayoutRepository
import com.example.market.domain.settlement.PayoutId
import com.example.market.domain.settlement.PayoutStatus
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class MarkPayoutCompletedService(
    private val payouts: PayoutRepository,
    private val events: EventPublisher,
    private val clock: Clock,
) : MarkPayoutCompletedUseCase {

    @Transactional
    override fun markCompleted(payoutId: PayoutId) {
        val payout = payouts.findById(payoutId)
            .orElseThrow { PayoutNotFoundException(payoutId) }
        if (payout.status == PayoutStatus.COMPLETED) {
            log.info("payout already completed — idempotent skip {}", payoutId)
            return
        }
        val ev = payout.complete(clock.instant())
        payouts.save(payout)
        events.publish(ev)
        log.info("payout completed {}", payoutId)
    }

    companion object {
        private val log = LoggerFactory.getLogger(MarkPayoutCompletedService::class.java)
    }
}
