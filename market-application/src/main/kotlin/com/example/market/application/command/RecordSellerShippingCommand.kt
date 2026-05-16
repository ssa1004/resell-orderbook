package com.example.market.application.command

import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.TradeId

@JvmRecord
data class RecordSellerShippingCommand(
    val requestor: UserId,    // 본인 거래만 가능
    val tradeId: TradeId,
    val trackingNumber: String,
)
