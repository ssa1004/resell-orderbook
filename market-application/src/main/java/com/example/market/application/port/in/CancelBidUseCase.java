package com.example.market.application.port.in;

import com.example.market.application.command.CancelBidCommand;

public interface CancelBidUseCase {
    void cancel(CancelBidCommand command);
}
