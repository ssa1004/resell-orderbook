package com.example.market.application.port.in;

import com.example.market.application.command.BuyNowCommand;
import com.example.market.domain.trading.Trade;

public interface BuyNowUseCase {
    Trade buyNow(BuyNowCommand command);
}
