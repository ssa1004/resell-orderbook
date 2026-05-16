package com.example.market.application.command

import com.example.market.domain.catalog.SkuId
import com.example.market.domain.shared.Money
import com.example.market.domain.shared.UserId

@JvmRecord
data class PlaceBidCommand(
    val idempotencyKey: String,
    val buyerId: UserId,
    val skuId: SkuId,
    val bidPrice: Money,
)
