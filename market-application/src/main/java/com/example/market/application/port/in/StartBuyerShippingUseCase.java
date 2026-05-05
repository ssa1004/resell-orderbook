package com.example.market.application.port.in;

import com.example.market.application.command.StartBuyerShippingCommand;

public interface StartBuyerShippingUseCase {
    void start(StartBuyerShippingCommand command);
}
