package com.example.market.application.service

import com.example.market.application.command.CompleteTradeCommand
import com.example.market.application.exception.TradeNotFoundException
import com.example.market.application.exception.UnauthorizedTradeOperationException
import com.example.market.application.port.`in`.CompleteTradeUseCase
import com.example.market.application.port.out.EventPublisher
import com.example.market.application.port.out.TradeRepository
import com.example.market.domain.trading.Trade
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class CompleteTradeService(
    private val trades: TradeRepository,
    private val events: EventPublisher,
    private val clock: Clock,
) : CompleteTradeUseCase {

    @Transactional
    override fun complete(command: CompleteTradeCommand): Trade {
        val trade = trades.findById(command.tradeId)
            .orElseThrow { TradeNotFoundException(command.tradeId) }
        if (trade.buyerId != command.requestor) {
            throw UnauthorizedTradeOperationException(trade.id, command.requestor, "complete")
        }
        val ev = trade.complete(clock.instant())
        trades.save(trade)
        events.publish(ev)
        log.info("trade completed id={} sellerNet={}", trade.id, ev.sellerNet)
        return trade
    }

    companion object {
        private val log = LoggerFactory.getLogger(CompleteTradeService::class.java)
    }
}
