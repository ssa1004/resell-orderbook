package com.example.market.application.exception;

import com.example.market.domain.trading.TradeId;

public class TradeNotFoundException extends RuntimeException {
    public TradeNotFoundException(TradeId id) {
        super("trade not found: " + id);
    }
}
