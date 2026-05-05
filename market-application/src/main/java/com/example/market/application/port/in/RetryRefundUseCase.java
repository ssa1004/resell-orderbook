package com.example.market.application.port.in;

import com.example.market.domain.settlement.RefundId;

/**
 * 운영자 admin endpoint — Refund.fail 상태에서 PG.refund 재시도.
 */
public interface RetryRefundUseCase {
    void retry(RefundId refundId);
}
