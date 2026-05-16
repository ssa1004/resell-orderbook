package com.example.market.application.service

import com.example.market.application.command.StartBuyerShippingCommand
import com.example.market.application.exception.TradeNotFoundException
import com.example.market.application.port.`in`.StartBuyerShippingUseCase
import com.example.market.application.port.out.EventPublisher
import com.example.market.application.port.out.TradeRepository
import com.example.market.domain.trading.TradeStatus
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * InspectionPassed 컨슈머. trackingNumber 는 검수센터의 발송 시스템에서 받음.
 *
 * idempotent: trade 가 이미 BUYER_SHIPPING 또는 그 이후면 skip.
 */
@Service
open class StartBuyerShippingService(
    private val trades: TradeRepository,
    private val events: EventPublisher,
    private val clock: Clock,
) : StartBuyerShippingUseCase {

    @Transactional
    override fun start(command: StartBuyerShippingCommand) {
        val trade = trades.findById(command.tradeId)
            .orElseThrow { TradeNotFoundException(command.tradeId) }
        if (trade.status != TradeStatus.INSPECTION_PASSED) {
            log.info("startBuyerShipping idempotent skip — trade {} in {}", trade.id, trade.status)
            return
        }
        val ev = trade.startBuyerShipping(clock.instant())
        trades.save(trade)
        events.publish(ev)
        log.info(
            "buyer shipping started trade={} tracking={}",
            trade.id, SensitiveLogging.mask(command.trackingNumber),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(StartBuyerShippingService::class.java)
    }
}
