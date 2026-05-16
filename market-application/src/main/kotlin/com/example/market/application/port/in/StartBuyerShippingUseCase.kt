package com.example.market.application.port.`in`

import com.example.market.application.command.StartBuyerShippingCommand

interface StartBuyerShippingUseCase {
    fun start(command: StartBuyerShippingCommand)
}
