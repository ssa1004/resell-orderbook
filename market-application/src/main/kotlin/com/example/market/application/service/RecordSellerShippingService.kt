package com.example.market.application.service

import com.example.market.application.command.RecordSellerShippingCommand
import com.example.market.application.exception.TradeNotFoundException
import com.example.market.application.exception.UnauthorizedTradeOperationException
import com.example.market.application.port.`in`.RecordSellerShippingUseCase
import com.example.market.application.port.out.EventPublisher
import com.example.market.application.port.out.TradeRepository
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class RecordSellerShippingService(
    private val trades: TradeRepository,
    private val events: EventPublisher,
    private val clock: Clock,
) : RecordSellerShippingUseCase {

    @Transactional
    override fun recordShipping(command: RecordSellerShippingCommand) {
        val trade = trades.findById(command.tradeId)
            .orElseThrow { TradeNotFoundException(command.tradeId) }
        if (trade.sellerId != command.requestor) {
            throw UnauthorizedTradeOperationException(trade.id, command.requestor, "shipping")
        }
        val now = clock.instant()
        val ev = trade.startSellerShipping(now)
        trades.save(trade)
        events.publish(ev)
        log.info(
            "seller shipping recorded trade={} tracking={}",
            trade.id, SensitiveLogging.mask(command.trackingNumber),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(RecordSellerShippingService::class.java)
    }
}
