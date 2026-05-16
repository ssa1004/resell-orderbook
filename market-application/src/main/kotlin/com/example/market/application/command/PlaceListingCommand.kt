package com.example.market.application.command

import com.example.market.domain.catalog.SkuId
import com.example.market.domain.shared.Money
import com.example.market.domain.shared.UserId

@JvmRecord
data class PlaceListingCommand(
    val idempotencyKey: String,
    val sellerId: UserId,
    val skuId: SkuId,
    val askPrice: Money,
)
