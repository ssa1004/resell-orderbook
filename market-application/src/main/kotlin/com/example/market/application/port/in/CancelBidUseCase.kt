package com.example.market.application.port.`in`

import com.example.market.application.command.CancelBidCommand

interface CancelBidUseCase {
    fun cancel(command: CancelBidCommand)
}
