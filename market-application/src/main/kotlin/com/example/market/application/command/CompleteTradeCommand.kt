package com.example.market.application.command

import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.TradeId

@JvmRecord
data class CompleteTradeCommand(val requestor: UserId, val tradeId: TradeId)
