package com.example.market.application.port.`in`

/**
 * Spring Batch — 만료(expiresAt 지난) ACTIVE Listing 을 EXPIRED 로 일괄 마킹.
 */
interface ExpireStaleListingsUseCase {
    fun expireBatch(batchSize: Int): Int
}
