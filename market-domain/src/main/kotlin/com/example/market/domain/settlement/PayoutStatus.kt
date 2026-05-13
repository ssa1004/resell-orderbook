package com.example.market.domain.settlement

enum class PayoutStatus {
    /** 정산 예정 */
    SCHEDULED,

    /** 송금 요청됨 */
    SENT,

    /** 정산 완료 */
    COMPLETED,

    /** 송금 실패 */
    FAILED;

    fun isTerminal(): Boolean = this == COMPLETED || this == FAILED
}
