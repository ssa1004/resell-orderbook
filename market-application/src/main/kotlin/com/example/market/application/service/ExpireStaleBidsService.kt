package com.example.market.application.service

import com.example.market.application.port.`in`.ExpireStaleBidsUseCase
import com.example.market.application.port.out.BidRepository
import com.example.market.application.port.out.StaleBidScanner
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class ExpireStaleBidsService(
    private val bids: BidRepository,
    private val scanner: StaleBidScanner,
    private val clock: Clock,
) : ExpireStaleBidsUseCase {

    @Transactional
    override fun expireBatch(batchSize: Int): Int {
        val now = clock.instant()
        val stale = scanner.findStaleActive(now, batchSize)
        for (b in stale) {
            b.expire(now)
            bids.save(b)
        }
        if (stale.isNotEmpty()) {
            log.info("expired {} bids", stale.size)
        }
        return stale.size
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExpireStaleBidsService::class.java)
    }
}
