package com.example.market.application.port.in;

import com.example.market.application.command.PlaceListingCommand;
import com.example.market.domain.trading.ListingId;
import com.example.market.domain.trading.TradeId;

import java.util.Optional;

public interface PlaceListingUseCase {

    PlaceListingResult place(PlaceListingCommand command);

    /** 매칭 성공 시 matchedTradeId 가 채워짐. 실패 (호가창에 등록만) 시 empty. */
    record PlaceListingResult(ListingId listingId, Optional<TradeId> matchedTradeId) {}
}
