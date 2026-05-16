package com.example.market.application.port.`in`

import com.example.market.domain.settlement.PayoutId

/**
 * 은행 송금 완료 콜백 → Payout.complete.
 */
interface MarkPayoutCompletedUseCase {
    fun markCompleted(payoutId: PayoutId)
}
