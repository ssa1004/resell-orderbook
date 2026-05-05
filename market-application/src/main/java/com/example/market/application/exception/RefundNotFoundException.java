package com.example.market.application.exception;

import com.example.market.domain.settlement.RefundId;

public class RefundNotFoundException extends RuntimeException {
    public RefundNotFoundException(RefundId id) {
        super("refund not found: " + id);
    }
}
