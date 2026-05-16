package com.example.market.application.port.`in`

import com.example.market.application.command.SellNowCommand
import com.example.market.domain.trading.Trade

interface SellNowUseCase {
    fun sellNow(command: SellNowCommand): Trade
}
