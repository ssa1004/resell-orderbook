package com.example.market.adapter.web.dto

import com.example.market.application.command.RecordSellerShippingCommand
import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.TradeId
import jakarta.validation.constraints.NotBlank

data class RecordSellerShippingRequest(@field:NotBlank val trackingNumber: String) {
    fun toCommand(requestor: UserId, tradeId: TradeId) =
        RecordSellerShippingCommand(requestor, tradeId, trackingNumber)
}
