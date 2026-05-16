package com.example.market.application.port.`in`

import com.example.market.application.command.RecordSellerShippingCommand

interface RecordSellerShippingUseCase {
    fun recordShipping(command: RecordSellerShippingCommand)
}
