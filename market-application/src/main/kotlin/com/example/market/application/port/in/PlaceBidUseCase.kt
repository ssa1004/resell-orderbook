package com.example.market.application.port.`in`

import com.example.market.application.command.PlaceBidCommand
import com.example.market.domain.trading.BidId
import com.example.market.domain.trading.TradeId
import java.util.Optional

interface PlaceBidUseCase {

    fun place(command: PlaceBidCommand): PlaceBidResult

    @JvmRecord
    data class PlaceBidResult(val bidId: BidId, val matchedTradeId: Optional<TradeId>)
}
