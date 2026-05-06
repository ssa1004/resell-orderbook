package com.example.market.application.exception;

import com.example.market.domain.settlement.RefundId;

public class RefundNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RefundNotFoundException(RefundId id) {
        super("refund not found: " + id);
    }
}
