package com.example.market.domain.trading

import com.example.market.domain.catalog.SkuId
import com.example.market.domain.shared.DomainEvent
import com.example.market.domain.shared.Money
import com.example.market.domain.shared.UserId
import java.time.Instant

/**
 * Listing(ASK) — 판매자가 등록한 *판매 호가*. "이 가격에 팔겠다".
 *
 * 상태: ACTIVE → MATCHED (체결) / CANCELLED / EXPIRED.
 * 매칭 우선순위 (price-time priority — 거래소 표준): 가격 낮은 순 → 같은 가격이면 먼저
 * 등록된 순.
 *
 * record-style accessor (id(), skuId(), status() 등) 는 `@get:JvmName` 으로 Java/Kotlin
 * 양쪽 호출자 호환 유지.
 */
class Listing private constructor(
    @get:JvmName("id") val id: ListingId,
    @get:JvmName("skuId") val skuId: SkuId,
    @get:JvmName("sellerId") val sellerId: UserId,
    @get:JvmName("askPrice") val askPrice: Money,
    @get:JvmName("expiresAt") val expiresAt: Instant,
    @get:JvmName("createdAt") val createdAt: Instant,
    status: ListingStatus,
    matchedTradeId: TradeId?,
    @get:JvmName("version") val version: Long,
) {

    @get:JvmName("status")
    var status: ListingStatus = status
        private set

    @get:JvmName("matchedTradeId")
    var matchedTradeId: TradeId? = matchedTradeId
        private set

    fun markMatched(tradeId: TradeId) {
        check(status == ListingStatus.ACTIVE) {
            "listing must be ACTIVE to match, was $status"
        }
        this.status = ListingStatus.MATCHED
        this.matchedTradeId = tradeId
    }

    /**
     * 판매자 본인만 자신의 호가를 취소할 수 있다.
     *
     * @throws IllegalStateException ACTIVE 가 아니면
     * @throws ListingOwnershipViolation requestor 가 sellerId 와 다르면
     */
    fun cancel(requestor: UserId) {
        if (sellerId != requestor) {
            throw ListingOwnershipViolation(id, sellerId, requestor)
        }
        check(status == ListingStatus.ACTIVE) {
            "listing must be ACTIVE to cancel, was $status"
        }
        this.status = ListingStatus.CANCELLED
    }

    fun expire(now: Instant) {
        if (status != ListingStatus.ACTIVE) return  // 같은 호가에 두 번 호출되어도 안전 (멱등)
        check(!now.isBefore(expiresAt)) { "not yet expired: $expiresAt" }
        this.status = ListingStatus.EXPIRED
    }

    fun isActive(): Boolean = status == ListingStatus.ACTIVE

    /**
     * 매칭 가능 여부 — ACTIVE 이고 아직 만료되지 않음. MatchEngine 이 호출해서 사용.
     * status 컬럼이 ACTIVE 인 채로 만료 시각을 넘긴 호가가 잘못 체결되는 것을 막아준다.
     */
    fun isMatchableAt(now: Instant): Boolean = isActive() && now.isBefore(expiresAt)

    /** 호가창 실시간 push 용 — 매칭이 안 된 채 신규 호가만 등록된 경우에 발행. */
    fun placed(now: Instant): ListingPlaced = ListingPlaced(id, skuId, sellerId, askPrice, now)

    /** 호가창 실시간 push 용 — 호가가 취소되었을 때 발행. */
    fun cancelled(now: Instant): ListingCancelled = ListingCancelled(id, skuId, now)

    companion object {
        /** 판매 호가 등록. 가격은 양수, 만료는 30일 기본. */
        @JvmStatic
        fun place(skuId: SkuId, sellerId: UserId, askPrice: Money, now: Instant): Listing {
            require(askPrice.isPositive) { "askPrice must be positive" }
            val expiresAt = now.plusSeconds(30L * 24 * 3600)
            return Listing(
                id = ListingId.newId(),
                skuId = skuId,
                sellerId = sellerId,
                askPrice = askPrice,
                expiresAt = expiresAt,
                createdAt = now,
                status = ListingStatus.ACTIVE,
                matchedTradeId = null,
                version = 0L,
            )
        }

        @JvmStatic
        fun restore(
            id: ListingId,
            skuId: SkuId,
            sellerId: UserId,
            askPrice: Money,
            expiresAt: Instant,
            createdAt: Instant,
            status: ListingStatus,
            matchedTradeId: TradeId?,
            version: Long,
        ): Listing = Listing(
            id = id,
            skuId = skuId,
            sellerId = sellerId,
            askPrice = askPrice,
            expiresAt = expiresAt,
            createdAt = createdAt,
            status = status,
            matchedTradeId = matchedTradeId,
            version = version,
        )
    }

    data class ListingPlaced(
        @get:JvmName("listingId") val listingId: ListingId,
        @get:JvmName("skuId") val skuId: SkuId,
        @get:JvmName("sellerId") val sellerId: UserId,
        @get:JvmName("askPrice") val askPrice: Money,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = listingId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class ListingCancelled(
        @get:JvmName("listingId") val listingId: ListingId,
        @get:JvmName("skuId") val skuId: SkuId,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = listingId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }
}
