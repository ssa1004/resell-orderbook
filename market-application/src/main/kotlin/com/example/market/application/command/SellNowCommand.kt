package com.example.market.application.command

import com.example.market.domain.catalog.SkuId
import com.example.market.domain.shared.UserId

@JvmRecord
data class SellNowCommand(
    val idempotencyKey: String,
    val sellerId: UserId,
    val skuId: SkuId,
)
