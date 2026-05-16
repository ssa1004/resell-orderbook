package com.example.market.application.service

import com.example.market.application.port.`in`.ExpireStaleListingsUseCase
import com.example.market.application.port.out.EventPublisher
import com.example.market.application.port.out.ListingRepository
import com.example.market.application.port.out.StaleListingScanner
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class ExpireStaleListingsService(
    private val listings: ListingRepository,
    private val scanner: StaleListingScanner,
    @Suppress("unused") private val events: EventPublisher,
    private val clock: Clock,
) : ExpireStaleListingsUseCase {

    @Transactional
    override fun expireBatch(batchSize: Int): Int {
        val now = clock.instant()
        val stale = scanner.findStaleActive(now, batchSize)
        for (l in stale) {
            l.expire(now)
            listings.save(l)
        }
        if (stale.isNotEmpty()) {
            log.info("expired {} listings", stale.size)
        }
        return stale.size
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExpireStaleListingsService::class.java)
    }
}
