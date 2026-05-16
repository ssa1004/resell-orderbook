package com.example.market.application.port.`in`

import com.example.market.application.command.CancelListingCommand

interface CancelListingUseCase {
    fun cancel(command: CancelListingCommand)
}
