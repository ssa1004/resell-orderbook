package com.example.market.domain.trading

enum class ListingStatus {
    /** 호가창에 노출 중 */
    ACTIVE,

    /** 거래 체결됨 */
    MATCHED,

    /** 판매자가 취소 */
    CANCELLED,

    /** 30일 경과 자동 만료 */
    EXPIRED;

    fun isTerminal(): Boolean = this != ACTIVE
}
