package com.example.market.application.port.`in`

interface ExpireStaleBidsUseCase {
    fun expireBatch(batchSize: Int): Int
}
