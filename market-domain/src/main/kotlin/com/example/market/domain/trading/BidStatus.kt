package com.example.market.domain.trading

enum class BidStatus {
    ACTIVE,
    MATCHED,
    CANCELLED,
    EXPIRED;

    fun isTerminal(): Boolean = this != ACTIVE
}
