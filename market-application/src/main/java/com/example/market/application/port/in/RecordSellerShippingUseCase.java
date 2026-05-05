package com.example.market.application.port.in;

import com.example.market.application.command.RecordSellerShippingCommand;

public interface RecordSellerShippingUseCase {
    void recordShipping(RecordSellerShippingCommand command);
}
