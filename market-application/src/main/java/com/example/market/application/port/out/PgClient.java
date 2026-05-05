package com.example.market.application.port.out;

import com.example.market.domain.shared.Money;

/**
 * 외부 PG (Payment Gateway). Resilience4j Circuit Breaker + Retry 적용.
 * fallback 시 {@link AuthorizeResult#rejected(String, String)} 반환.
 */
public interface PgClient {

    AuthorizeResult authorize(AuthorizeRequest request);
    RefundResult refund(RefundRequest request);

    record AuthorizeRequest(String idempotencyKey, Money amount, String tradeId, String buyerId) {}
    record AuthorizeResult(boolean approved, String pgPaymentId, String errorCode, String errorMessage) {
        public static AuthorizeResult approved(String pgPaymentId) {
            return new AuthorizeResult(true, pgPaymentId, null, null);
        }
        public static AuthorizeResult rejected(String code, String msg) {
            return new AuthorizeResult(false, null, code, msg);
        }
    }

    record RefundRequest(String pgPaymentId, Money amount, String reason) {}
    record RefundResult(boolean approved, String pgRefundId, String errorMessage) {
        public static RefundResult approved(String pgRefundId) {
            return new RefundResult(true, pgRefundId, null);
        }
        public static RefundResult rejected(String msg) {
            return new RefundResult(false, null, msg);
        }
    }
}
