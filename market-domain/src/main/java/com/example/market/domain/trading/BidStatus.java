package com.example.market.domain.trading;

public enum BidStatus {
    ACTIVE, MATCHED, CANCELLED, EXPIRED;
    public boolean isTerminal() { return this != ACTIVE; }
}
