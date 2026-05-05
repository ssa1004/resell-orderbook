package com.example.market.application.port.in;

import com.example.market.application.command.PlaceBidCommand;
import com.example.market.domain.trading.BidId;
import com.example.market.domain.trading.TradeId;

import java.util.Optional;

public interface PlaceBidUseCase {

    PlaceBidResult place(PlaceBidCommand command);

    record PlaceBidResult(BidId bidId, Optional<TradeId> matchedTradeId) {}
}
