package com.example.market.application.port.out

import com.example.market.domain.shared.Money

/**
 * 외부 PG (Payment Gateway). Resilience4j Circuit Breaker + Retry 적용.
 * fallback 시 [AuthorizeResult.rejected] 반환.
 */
interface PgClient {

    fun authorize(request: AuthorizeRequest): AuthorizeResult
    fun refund(request: RefundRequest): RefundResult

    @JvmRecord
    data class AuthorizeRequest(
        val idempotencyKey: String,
        val amount: Money,
        val tradeId: String,
        val buyerId: String,
    )

    @JvmRecord
    data class AuthorizeResult(
        val approved: Boolean,
        val pgPaymentId: String?,
        val errorCode: String?,
        val errorMessage: String?,
    ) {
        companion object {
            @JvmStatic
            fun approved(pgPaymentId: String): AuthorizeResult =
                AuthorizeResult(true, pgPaymentId, null, null)

            @JvmStatic
            fun rejected(code: String, msg: String): AuthorizeResult =
                AuthorizeResult(false, null, code, msg)
        }
    }

    @JvmRecord
    data class RefundRequest(val pgPaymentId: String, val amount: Money, val reason: String?)

    @JvmRecord
    data class RefundResult(
        val approved: Boolean,
        val pgRefundId: String?,
        val errorMessage: String?,
    ) {
        companion object {
            @JvmStatic
            fun approved(pgRefundId: String): RefundResult = RefundResult(true, pgRefundId, null)

            @JvmStatic
            fun rejected(msg: String): RefundResult = RefundResult(false, null, msg)
        }
    }
}
