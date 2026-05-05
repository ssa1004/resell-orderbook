package com.example.market.application.port.in;

import com.example.market.domain.settlement.PayoutId;

/**
 * 은행 송금 완료 콜백 → Payout.complete.
 */
public interface MarkPayoutCompletedUseCase {
    void markCompleted(PayoutId payoutId);
}
