package com.example.market.application.port.`in`

import com.example.market.application.command.PlaceListingCommand
import com.example.market.domain.trading.ListingId
import com.example.market.domain.trading.TradeId
import java.util.Optional

interface PlaceListingUseCase {

    fun place(command: PlaceListingCommand): PlaceListingResult

    /** 매칭 성공 시 matchedTradeId 가 채워짐. 실패 (호가창에 등록만) 시 empty. */
    @JvmRecord
    data class PlaceListingResult(val listingId: ListingId, val matchedTradeId: Optional<TradeId>)
}
