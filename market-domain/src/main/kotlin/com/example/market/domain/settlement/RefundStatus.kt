package com.example.market.domain.settlement

enum class RefundStatus {
    REQUESTED, COMPLETED, FAILED;

    fun isTerminal(): Boolean = this == COMPLETED || this == FAILED
}
