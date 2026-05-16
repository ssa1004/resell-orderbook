package com.example.market.application.command

import com.example.market.domain.trading.TradeId

@JvmRecord
data class StartBuyerShippingCommand(val tradeId: TradeId, val trackingNumber: String)
