package com.example.market.domain.trading

import com.example.market.domain.catalog.SkuId
import com.example.market.domain.shared.DomainEvent
import com.example.market.domain.shared.Money
import com.example.market.domain.shared.UserId
import java.time.Instant

/**
 * Bid — 구매자가 등록한 *구매 호가*. "이 가격에 사겠다".
 * 매칭 우선순위: 가격 높은 순 → 시간 오래된 순.
 *
 * record-style accessor (id(), skuId(), status() 등) 는 `@get:JvmName` 으로 Java/Kotlin
 * 양쪽 호출자 호환 유지.
 */
class Bid private constructor(
    @get:JvmName("id") val id: BidId,
    @get:JvmName("skuId") val skuId: SkuId,
    @get:JvmName("buyerId") val buyerId: UserId,
    @get:JvmName("bidPrice") val bidPrice: Money,
    @get:JvmName("expiresAt") val expiresAt: Instant,
    @get:JvmName("createdAt") val createdAt: Instant,
    status: BidStatus,
    matchedTradeId: TradeId?,
    @get:JvmName("version") val version: Long,
) {

    @get:JvmName("status")
    var status: BidStatus = status
        private set

    @get:JvmName("matchedTradeId")
    var matchedTradeId: TradeId? = matchedTradeId
        private set

    fun markMatched(tradeId: TradeId) {
        check(status == BidStatus.ACTIVE) {
            "bid must be ACTIVE to match, was $status"
        }
        this.status = BidStatus.MATCHED
        this.matchedTradeId = tradeId
    }

    /**
     * 구매자 본인만 자신의 호가를 취소할 수 있다.
     *
     * @throws IllegalStateException ACTIVE 가 아니면
     * @throws BidOwnershipViolation requestor 가 buyerId 와 다르면
     */
    fun cancel(requestor: UserId) {
        if (buyerId != requestor) {
            throw BidOwnershipViolation(id, buyerId, requestor)
        }
        check(status == BidStatus.ACTIVE) {
            "bid must be ACTIVE to cancel, was $status"
        }
        this.status = BidStatus.CANCELLED
    }

    fun expire(now: Instant) {
        if (status != BidStatus.ACTIVE) return
        check(!now.isBefore(expiresAt)) { "not yet expired: $expiresAt" }
        this.status = BidStatus.EXPIRED
    }

    fun isActive(): Boolean = status == BidStatus.ACTIVE

    fun isMatchableAt(now: Instant): Boolean = isActive() && now.isBefore(expiresAt)

    fun placed(now: Instant): BidPlaced = BidPlaced(id, skuId, buyerId, bidPrice, now)

    fun cancelled(now: Instant): BidCancelled = BidCancelled(id, skuId, now)

    companion object {
        @JvmStatic
        fun place(skuId: SkuId, buyerId: UserId, bidPrice: Money, now: Instant): Bid {
            require(bidPrice.isPositive) { "bidPrice must be positive" }
            val expiresAt = now.plusSeconds(30L * 24 * 3600)
            return Bid(
                id = BidId.newId(),
                skuId = skuId,
                buyerId = buyerId,
                bidPrice = bidPrice,
                expiresAt = expiresAt,
                createdAt = now,
                status = BidStatus.ACTIVE,
                matchedTradeId = null,
                version = 0L,
            )
        }

        @JvmStatic
        fun restore(
            id: BidId,
            skuId: SkuId,
            buyerId: UserId,
            bidPrice: Money,
            expiresAt: Instant,
            createdAt: Instant,
            status: BidStatus,
            matchedTradeId: TradeId?,
            version: Long,
        ): Bid = Bid(
            id = id,
            skuId = skuId,
            buyerId = buyerId,
            bidPrice = bidPrice,
            expiresAt = expiresAt,
            createdAt = createdAt,
            status = status,
            matchedTradeId = matchedTradeId,
            version = version,
        )
    }

    data class BidPlaced(
        @get:JvmName("bidId") val bidId: BidId,
        @get:JvmName("skuId") val skuId: SkuId,
        @get:JvmName("buyerId") val buyerId: UserId,
        @get:JvmName("bidPrice") val bidPrice: Money,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = bidId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class BidCancelled(
        @get:JvmName("bidId") val bidId: BidId,
        @get:JvmName("skuId") val skuId: SkuId,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = bidId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }
}
