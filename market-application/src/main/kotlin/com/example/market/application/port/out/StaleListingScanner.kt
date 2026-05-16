package com.example.market.application.port.out

import com.example.market.domain.trading.Listing
import java.time.Instant

/**
 * Spring Batch reader 가 사용 — 만료된 ACTIVE Listing 페이지 단위 조회.
 */
interface StaleListingScanner {
    fun findStaleActive(cutoff: Instant, batchSize: Int): List<Listing>
}
