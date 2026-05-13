package com.example.market.domain.settlement

import com.example.market.domain.shared.DomainEvent
import com.example.market.domain.shared.Money
import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.TradeId
import java.time.Instant

/**
 * Refund — 검수 실패 시 구매자에게 *전액* 환불.
 *
 * `amount` 는 구매자가 결제 시점에 PG 에 결제한 총액 ([FeeSnapshot.buyerCharge]) —
 * 즉 거래가 + 구매자 수수료 + 검수비 + 배송비 모두 포함. 검수 실패는 검수센터/판매자 책임이므로
 * 구매자는 부담한 모든 비용을 돌려받는다.
 *
 * 흐름: Trade.startRefunding() → Refund.request() → PG.refund() 성공 시 Refund.complete() →
 * Trade.closeAsFailedAfterRefund(). 환불 실패 (PG 응답 실패) 시 Refund.fail() — 운영자 수동 처리.
 *
 * record-style accessor (id(), tradeId(), status() 등) 는 `@get:JvmName` 으로 Java/Kotlin
 * 양쪽 호출자 호환 유지.
 */
class Refund private constructor(
    @get:JvmName("id") val id: RefundId,
    @get:JvmName("tradeId") val tradeId: TradeId,
    @get:JvmName("buyerId") val buyerId: UserId,
    @get:JvmName("amount") val amount: Money,
    @get:JvmName("reason") val reason: String?,
    status: RefundStatus,
    pgRefundId: String?,
    @get:JvmName("requestedAt") val requestedAt: Instant,
    completedAt: Instant?,
    @get:JvmName("version") val version: Long,
) {

    @get:JvmName("status")
    var status: RefundStatus = status
        private set

    @get:JvmName("pgRefundId")
    var pgRefundId: String? = pgRefundId
        private set

    @get:JvmName("completedAt")
    var completedAt: Instant? = completedAt
        private set

    fun complete(pgRefundId: String, now: Instant): RefundCompleted {
        check(status == RefundStatus.REQUESTED) { "must be REQUESTED, was $status" }
        this.status = RefundStatus.COMPLETED
        this.pgRefundId = pgRefundId
        this.completedAt = now
        return RefundCompleted(id, tradeId, buyerId, amount, pgRefundId, now)
    }

    fun fail(reason: String, now: Instant): RefundFailed {
        check(status != RefundStatus.COMPLETED) { "already COMPLETED" }
        this.status = RefundStatus.FAILED
        this.completedAt = now
        return RefundFailed(id, tradeId, buyerId, reason, now)
    }

    companion object {
        @JvmStatic
        fun request(
            tradeId: TradeId,
            buyerId: UserId,
            amount: Money,
            reason: String?,
            now: Instant,
        ): Refund {
            require(amount.isPositive) { "amount must be positive" }
            return Refund(
                id = RefundId.newId(),
                tradeId = tradeId,
                buyerId = buyerId,
                amount = amount,
                reason = reason,
                status = RefundStatus.REQUESTED,
                pgRefundId = null,
                requestedAt = now,
                completedAt = null,
                version = 0L,
            )
        }

        @JvmStatic
        fun restore(
            id: RefundId,
            tradeId: TradeId,
            buyerId: UserId,
            amount: Money,
            reason: String?,
            status: RefundStatus,
            pgRefundId: String?,
            requestedAt: Instant,
            completedAt: Instant?,
            version: Long,
        ): Refund = Refund(
            id = id,
            tradeId = tradeId,
            buyerId = buyerId,
            amount = amount,
            reason = reason,
            status = status,
            pgRefundId = pgRefundId,
            requestedAt = requestedAt,
            completedAt = completedAt,
            version = version,
        )
    }

    data class RefundCompleted(
        @get:JvmName("refundId") val refundId: RefundId,
        @get:JvmName("tradeId") val tradeId: TradeId,
        @get:JvmName("buyerId") val buyerId: UserId,
        @get:JvmName("amount") val amount: Money,
        @get:JvmName("pgRefundId") val pgRefundId: String,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = refundId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class RefundFailed(
        @get:JvmName("refundId") val refundId: RefundId,
        @get:JvmName("tradeId") val tradeId: TradeId,
        @get:JvmName("buyerId") val buyerId: UserId,
        @get:JvmName("reason") val reason: String,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = refundId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }
}
