package com.example.market.application.port.in;

import com.example.market.application.command.CancelListingCommand;

public interface CancelListingUseCase {
    void cancel(CancelListingCommand command);
}
