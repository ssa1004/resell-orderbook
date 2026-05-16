package com.example.market.application.port.`in`

import com.example.market.application.command.BuyNowCommand
import com.example.market.domain.trading.Trade

interface BuyNowUseCase {
    fun buyNow(command: BuyNowCommand): Trade
}
