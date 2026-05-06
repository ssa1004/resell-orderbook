package com.example.market.application.exception;

import com.example.market.domain.trading.BidId;

public class BidNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public BidNotFoundException(BidId id) {
        super("bid not found: " + id);
    }
}
