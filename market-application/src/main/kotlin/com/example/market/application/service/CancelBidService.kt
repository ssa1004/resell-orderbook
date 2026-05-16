package com.example.market.application.service

import com.example.market.application.command.CancelBidCommand
import com.example.market.application.exception.BidNotFoundException
import com.example.market.application.port.`in`.CancelBidUseCase
import com.example.market.application.port.out.BidRepository
import com.example.market.application.port.out.EventPublisher
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class CancelBidService(
    private val bids: BidRepository,
    private val events: EventPublisher,
    private val clock: Clock,
) : CancelBidUseCase {

    @Transactional
    override fun cancel(command: CancelBidCommand) {
        val bid = bids.findById(command.bidId)
            .orElseThrow { BidNotFoundException(command.bidId) }
        bid.cancel(command.requestor)
        bids.save(bid)
        val now = clock.instant()
        events.publish(bid.cancelled(now))
        log.info("bid cancelled id={} by={}", bid.id, command.requestor)
    }

    companion object {
        private val log = LoggerFactory.getLogger(CancelBidService::class.java)
    }
}
