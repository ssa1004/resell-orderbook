package com.example.market.domain.settlement;

public enum RefundStatus {
    REQUESTED, COMPLETED, FAILED;
    public boolean isTerminal() { return this == COMPLETED || this == FAILED; }
}
