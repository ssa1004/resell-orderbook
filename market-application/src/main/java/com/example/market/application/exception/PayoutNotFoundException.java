package com.example.market.application.exception;

import com.example.market.domain.settlement.PayoutId;

public class PayoutNotFoundException extends RuntimeException {
    public PayoutNotFoundException(PayoutId id) {
        super("payout not found: " + id);
    }
}
