package com.example.market.application.service

import com.example.market.application.command.CancelListingCommand
import com.example.market.application.exception.ListingNotFoundException
import com.example.market.application.port.`in`.CancelListingUseCase
import com.example.market.application.port.out.EventPublisher
import com.example.market.application.port.out.ListingRepository
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class CancelListingService(
    private val listings: ListingRepository,
    private val events: EventPublisher,
    private val clock: Clock,
) : CancelListingUseCase {

    @Transactional
    override fun cancel(command: CancelListingCommand) {
        val listing = listings.findById(command.listingId)
            .orElseThrow { ListingNotFoundException(command.listingId) }
        // 도메인이 ownership invariant 보장 — 다른 사용자가 호출 시 ListingOwnershipViolation
        listing.cancel(command.requestor)
        listings.save(listing)
        val now = clock.instant()
        events.publish(listing.cancelled(now))
        log.info("listing cancelled id={} by={}", listing.id, command.requestor)
    }

    companion object {
        private val log = LoggerFactory.getLogger(CancelListingService::class.java)
    }
}
