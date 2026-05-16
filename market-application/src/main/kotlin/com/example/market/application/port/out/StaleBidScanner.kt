package com.example.market.application.port.out

import com.example.market.domain.trading.Bid
import java.time.Instant

interface StaleBidScanner {
    fun findStaleActive(cutoff: Instant, batchSize: Int): List<Bid>
}
