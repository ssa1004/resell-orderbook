package com.example.market.application.port.`in`

import com.example.market.application.command.CompleteTradeCommand
import com.example.market.domain.trading.Trade

interface CompleteTradeUseCase {
    fun complete(command: CompleteTradeCommand): Trade
}
