package com.example.market.application.port.in;

import com.example.market.application.command.CompleteTradeCommand;
import com.example.market.domain.trading.Trade;

public interface CompleteTradeUseCase {
    Trade complete(CompleteTradeCommand command);
}
