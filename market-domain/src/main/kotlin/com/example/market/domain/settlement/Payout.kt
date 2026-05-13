package com.example.market.domain.settlement

import com.example.market.domain.shared.DomainEvent
import com.example.market.domain.shared.Money
import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.TradeId
import java.time.Instant

/**
 * Payout — 판매자 정산. Trade.COMPLETED 이벤트로 트리거.
 *
 * 구조: tradeAmount → sellerCommission + processingFee 차감 → netAmount → 판매자 계좌로 송금.
 *
 * 금액들은 거래 시점에 freeze 된 [FeeSnapshot] 으로부터 받음.
 *
 * record-style accessor (id(), tradeId(), status() 등) 는 `@get:JvmName` 으로 Java/Kotlin
 * 양쪽 호출자 호환 유지.
 */
class Payout private constructor(
    @get:JvmName("id") val id: PayoutId,
    @get:JvmName("tradeId") val tradeId: TradeId,
    @get:JvmName("sellerId") val sellerId: UserId,
    @get:JvmName("tradeAmount") val tradeAmount: Money,
    @get:JvmName("sellerCommission") val sellerCommission: Money,
    @get:JvmName("processingFee") val processingFee: Money,
    @get:JvmName("netAmount") val netAmount: Money,
    status: PayoutStatus,
    bankTransferId: String?,
    @get:JvmName("createdAt") val createdAt: Instant,
    completedAt: Instant?,
    @get:JvmName("version") val version: Long,
) {

    @get:JvmName("status")
    var status: PayoutStatus = status
        private set

    @get:JvmName("bankTransferId")
    var bankTransferId: String? = bankTransferId
        private set

    @get:JvmName("completedAt")
    var completedAt: Instant? = completedAt
        private set

    fun send(bankTransferId: String, now: Instant): PayoutSent {
        check(status == PayoutStatus.SCHEDULED) { "must be SCHEDULED to send, was $status" }
        this.bankTransferId = bankTransferId
        this.status = PayoutStatus.SENT
        return PayoutSent(id, tradeId, sellerId, netAmount, bankTransferId, now)
    }

    fun complete(now: Instant): PayoutCompleted {
        check(status == PayoutStatus.SENT) { "must be SENT to complete, was $status" }
        this.status = PayoutStatus.COMPLETED
        this.completedAt = now
        return PayoutCompleted(id, tradeId, sellerId, netAmount, now)
    }

    fun fail(reason: String, now: Instant): PayoutFailed {
        check(status != PayoutStatus.COMPLETED) { "already COMPLETED" }
        this.status = PayoutStatus.FAILED
        this.completedAt = now
        return PayoutFailed(id, tradeId, sellerId, reason, now)
    }

    companion object {
        /** FeeSnapshot 으로부터 정산 일정을 만든다. */
        @JvmStatic
        fun schedule(
            tradeId: TradeId,
            sellerId: UserId,
            snapshot: FeeSnapshot,
            now: Instant,
        ): Payout {
            require(!snapshot.sellerNet.isNegative) {
                "sellerNet must be >= 0, was ${snapshot.sellerNet}"
            }
            return Payout(
                id = PayoutId.newId(),
                tradeId = tradeId,
                sellerId = sellerId,
                tradeAmount = snapshot.tradeAmount,
                sellerCommission = snapshot.sellerCommission,
                processingFee = snapshot.fixedProcessingFee,
                netAmount = snapshot.sellerNet,
                status = PayoutStatus.SCHEDULED,
                bankTransferId = null,
                createdAt = now,
                completedAt = null,
                version = 0L,
            )
        }

        @JvmStatic
        fun restore(
            id: PayoutId,
            tradeId: TradeId,
            sellerId: UserId,
            tradeAmount: Money,
            sellerCommission: Money,
            processingFee: Money,
            netAmount: Money,
            status: PayoutStatus,
            bankTransferId: String?,
            createdAt: Instant,
            completedAt: Instant?,
            version: Long,
        ): Payout = Payout(
            id = id,
            tradeId = tradeId,
            sellerId = sellerId,
            tradeAmount = tradeAmount,
            sellerCommission = sellerCommission,
            processingFee = processingFee,
            netAmount = netAmount,
            status = status,
            bankTransferId = bankTransferId,
            createdAt = createdAt,
            completedAt = completedAt,
            version = version,
        )
    }

    data class PayoutSent(
        @get:JvmName("payoutId") val payoutId: PayoutId,
        @get:JvmName("tradeId") val tradeId: TradeId,
        @get:JvmName("sellerId") val sellerId: UserId,
        @get:JvmName("netAmount") val netAmount: Money,
        @get:JvmName("bankTransferId") val bankTransferId: String,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = payoutId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class PayoutCompleted(
        @get:JvmName("payoutId") val payoutId: PayoutId,
        @get:JvmName("tradeId") val tradeId: TradeId,
        @get:JvmName("sellerId") val sellerId: UserId,
        @get:JvmName("netAmount") val netAmount: Money,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = payoutId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class PayoutFailed(
        @get:JvmName("payoutId") val payoutId: PayoutId,
        @get:JvmName("tradeId") val tradeId: TradeId,
        @get:JvmName("sellerId") val sellerId: UserId,
        @get:JvmName("reason") val reason: String,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = payoutId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }
}
