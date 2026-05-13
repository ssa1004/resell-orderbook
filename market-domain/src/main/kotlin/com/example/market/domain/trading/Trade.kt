package com.example.market.domain.trading

import com.example.market.domain.catalog.SkuId
import com.example.market.domain.settlement.FeePolicy
import com.example.market.domain.settlement.FeeSnapshot
import com.example.market.domain.shared.DomainEvent
import com.example.market.domain.shared.Money
import com.example.market.domain.shared.UserId
import java.time.Instant

/**
 * Trade — 매칭된 거래. Listing(ASK = 판매 호가) 과 Bid (구매 호가) 가 만나면 생성된다.
 *
 * 체결가 결정: **먼저 등록되어 호가창에 있던 호가의 가격** (taker/maker 모델 — 나중에 들어온
 * 호가 = taker 가, 미리 들어와 있던 호가 = maker 의 가격을 받아간다). 호출자가 결정해서 넘긴다.
 *
 * 매칭 시점에 [FeeSnapshot] 을 스냅샷으로 고정 (freeze) 한다 — 정책이 나중에 바뀌어도
 * 이 거래의 수수료 명세는 변하지 않는다.
 *
 * 상태 머신 ([TradeStatus] 가 단계별로 정의된 상태 흐름):
 * ```
 *  CREATED ──cancelOnPaymentFailure──▶ FAILED
 *     │
 *     ▼
 *  PAYMENT_AUTHORIZED → SELLER_SHIPPING → INSPECTION_PENDING
 *     │
 *     ├─ INSPECTION_PASSED → BUYER_SHIPPING → COMPLETED
 *     └─ INSPECTION_FAILED → REFUNDING → FAILED
 * ```
 *
 * record-style accessor (id(), price(), feeSnapshot(), status() 등) 는 `@get:JvmName` 으로
 * Java/Kotlin 양쪽 호출자 호환 유지.
 */
class Trade private constructor(
    @get:JvmName("id") val id: TradeId,
    @get:JvmName("skuId") val skuId: SkuId,
    @get:JvmName("listingId") val listingId: ListingId,
    @get:JvmName("bidId") val bidId: BidId,
    @get:JvmName("sellerId") val sellerId: UserId,
    @get:JvmName("buyerId") val buyerId: UserId,
    /** 체결가 */
    @get:JvmName("price") val price: Money,
    /** 매칭 순간의 수수료 명세 (이후 정책이 바뀌어도 불변) */
    @get:JvmName("feeSnapshot") val feeSnapshot: FeeSnapshot,
    status: TradeStatus,
    pgPaymentId: String?,
    inspectionFailReason: String?,
    @get:JvmName("createdAt") val createdAt: Instant,
    updatedAt: Instant,
    @get:JvmName("version") val version: Long,
) {

    @get:JvmName("status")
    var status: TradeStatus = status
        private set

    @get:JvmName("pgPaymentId")
    var pgPaymentId: String? = pgPaymentId
        private set

    @get:JvmName("inspectionFailReason")
    var inspectionFailReason: String? = inspectionFailReason
        private set

    @get:JvmName("updatedAt")
    var updatedAt: Instant = updatedAt
        private set

    // ── 상태 전이 ──────────────────────────────────────

    fun matched(now: Instant): TradeMatched = TradeMatched(
        id, skuId, listingId, bidId, sellerId, buyerId, price,
        feeSnapshot.buyerCharge, feeSnapshot.sellerNet, now,
    )

    fun authorizePayment(pgPaymentId: String, now: Instant): PaymentAuthorized {
        requireStatus(TradeStatus.CREATED, "authorizePayment")
        this.pgPaymentId = pgPaymentId
        this.status = TradeStatus.PAYMENT_AUTHORIZED
        this.updatedAt = now
        return PaymentAuthorized(id, pgPaymentId, feeSnapshot.buyerCharge, now)
    }

    /**
     * 결제 게이트웨이(PG) 의 결제 승인이 실패했을 때 호출 — CREATED 상태에서 바로 FAILED 로 종착.
     */
    fun cancelOnPaymentFailure(reason: String, now: Instant): PaymentRejected {
        requireStatus(TradeStatus.CREATED, "cancelOnPaymentFailure")
        this.status = TradeStatus.FAILED
        this.updatedAt = now
        return PaymentRejected(id, reason, now)
    }

    fun startSellerShipping(now: Instant): SellerShippingRequested {
        requireStatus(TradeStatus.PAYMENT_AUTHORIZED, "startSellerShipping")
        this.status = TradeStatus.SELLER_SHIPPING
        this.updatedAt = now
        return SellerShippingRequested(id, sellerId, now)
    }

    fun arriveAtInspection(now: Instant): InspectionRequested {
        requireStatus(TradeStatus.SELLER_SHIPPING, "arriveAtInspection")
        this.status = TradeStatus.INSPECTION_PENDING
        this.updatedAt = now
        return InspectionRequested(id, skuId, now)
    }

    fun passInspection(now: Instant): InspectionPassed {
        requireStatus(TradeStatus.INSPECTION_PENDING, "passInspection")
        this.status = TradeStatus.INSPECTION_PASSED
        this.updatedAt = now
        return InspectionPassed(id, now)
    }

    fun failInspection(reason: String, now: Instant): InspectionFailed {
        requireStatus(TradeStatus.INSPECTION_PENDING, "failInspection")
        this.status = TradeStatus.INSPECTION_FAILED
        this.inspectionFailReason = reason
        this.updatedAt = now
        return InspectionFailed(id, reason, now)
    }

    fun startBuyerShipping(now: Instant): BuyerShippingStarted {
        requireStatus(TradeStatus.INSPECTION_PASSED, "startBuyerShipping")
        this.status = TradeStatus.BUYER_SHIPPING
        this.updatedAt = now
        return BuyerShippingStarted(id, buyerId, now)
    }

    fun complete(now: Instant): TradeCompleted {
        requireStatus(TradeStatus.BUYER_SHIPPING, "complete")
        this.status = TradeStatus.COMPLETED
        this.updatedAt = now
        return TradeCompleted(id, sellerId, buyerId, price, feeSnapshot.sellerNet, now)
    }

    fun startRefunding(now: Instant): RefundingStarted {
        requireStatus(TradeStatus.INSPECTION_FAILED, "startRefunding")
        this.status = TradeStatus.REFUNDING
        this.updatedAt = now
        return RefundingStarted(id, buyerId, feeSnapshot.buyerCharge, inspectionFailReason, now)
    }

    /**
     * 환불 처리가 모두 완료된 후 거래를 종착(FAILED) 상태로 마감.
     *
     * 의미: REFUNDING 단계에서 PG 환불이 성공해 더 이상 진행할 작업이 없을 때 호출.
     * "거래 자체가 실패로 끝났다" 를 기록 — 더 이상 다른 상태로 못 가는 종착(terminal)
     * 상태로 진입.
     */
    fun closeAsFailedAfterRefund(now: Instant): TradeFailed {
        check(status == TradeStatus.REFUNDING) {
            "closeAsFailedAfterRefund() requires REFUNDING, was $status"
        }
        this.status = TradeStatus.FAILED
        this.updatedAt = now
        return TradeFailed(id, inspectionFailReason, now)
    }

    private fun requireStatus(expected: TradeStatus, op: String) {
        check(status == expected) { "$op() requires $expected, was $status" }
    }

    companion object {
        /** 매칭 시 Listing + Bid + 정책으로 Trade 를 생성. 정책은 이 순간 snapshot 으로 고정된다. */
        @JvmStatic
        fun match(
            listing: Listing,
            bid: Bid,
            executionPrice: Money,
            feePolicy: FeePolicy,
            now: Instant,
        ): Trade {
            require(listing.skuId == bid.skuId) { "listing/bid Sku mismatch" }
            check(listing.isActive()) { "listing not ACTIVE: ${listing.status}" }
            check(bid.isActive()) { "bid not ACTIVE: ${bid.status}" }
            require(listing.sellerId != bid.buyerId) {
                "self-trade not allowed: ${listing.sellerId}"
            }
            val snap = feePolicy.snapshotFor(executionPrice)
            return Trade(
                id = TradeId.newId(),
                skuId = listing.skuId,
                listingId = listing.id,
                bidId = bid.id,
                sellerId = listing.sellerId,
                buyerId = bid.buyerId,
                price = executionPrice,
                feeSnapshot = snap,
                status = TradeStatus.CREATED,
                pgPaymentId = null,
                inspectionFailReason = null,
                createdAt = now,
                updatedAt = now,
                version = 0L,
            )
        }

        @JvmStatic
        fun restore(
            id: TradeId,
            skuId: SkuId,
            listingId: ListingId,
            bidId: BidId,
            sellerId: UserId,
            buyerId: UserId,
            price: Money,
            feeSnapshot: FeeSnapshot,
            status: TradeStatus,
            pgPaymentId: String?,
            inspectionFailReason: String?,
            createdAt: Instant,
            updatedAt: Instant,
            version: Long,
        ): Trade = Trade(
            id = id,
            skuId = skuId,
            listingId = listingId,
            bidId = bidId,
            sellerId = sellerId,
            buyerId = buyerId,
            price = price,
            feeSnapshot = feeSnapshot,
            status = status,
            pgPaymentId = pgPaymentId,
            inspectionFailReason = inspectionFailReason,
            createdAt = createdAt,
            updatedAt = updatedAt,
            version = version,
        )
    }

    // ── domain events ──────────────────────────────────────

    data class TradeMatched(
        @get:JvmName("tradeId") val tradeId: TradeId,
        @get:JvmName("skuId") val skuId: SkuId,
        @get:JvmName("listingId") val listingId: ListingId,
        @get:JvmName("bidId") val bidId: BidId,
        @get:JvmName("sellerId") val sellerId: UserId,
        @get:JvmName("buyerId") val buyerId: UserId,
        @get:JvmName("price") val price: Money,
        @get:JvmName("buyerCharge") val buyerCharge: Money,
        @get:JvmName("sellerNet") val sellerNet: Money,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = tradeId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class PaymentAuthorized(
        @get:JvmName("tradeId") val tradeId: TradeId,
        @get:JvmName("pgPaymentId") val pgPaymentId: String,
        @get:JvmName("amount") val amount: Money,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = tradeId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class PaymentRejected(
        @get:JvmName("tradeId") val tradeId: TradeId,
        @get:JvmName("reason") val reason: String,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = tradeId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class SellerShippingRequested(
        @get:JvmName("tradeId") val tradeId: TradeId,
        @get:JvmName("sellerId") val sellerId: UserId,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = tradeId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class InspectionRequested(
        @get:JvmName("tradeId") val tradeId: TradeId,
        @get:JvmName("skuId") val skuId: SkuId,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = tradeId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class InspectionPassed(
        @get:JvmName("tradeId") val tradeId: TradeId,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = tradeId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class InspectionFailed(
        @get:JvmName("tradeId") val tradeId: TradeId,
        @get:JvmName("reason") val reason: String,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = tradeId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class BuyerShippingStarted(
        @get:JvmName("tradeId") val tradeId: TradeId,
        @get:JvmName("buyerId") val buyerId: UserId,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = tradeId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class TradeCompleted(
        @get:JvmName("tradeId") val tradeId: TradeId,
        @get:JvmName("sellerId") val sellerId: UserId,
        @get:JvmName("buyerId") val buyerId: UserId,
        @get:JvmName("price") val price: Money,
        @get:JvmName("sellerNet") val sellerNet: Money,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = tradeId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class RefundingStarted(
        @get:JvmName("tradeId") val tradeId: TradeId,
        @get:JvmName("buyerId") val buyerId: UserId,
        @get:JvmName("buyerCharge") val buyerCharge: Money,
        @get:JvmName("reason") val reason: String?,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = tradeId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class TradeFailed(
        @get:JvmName("tradeId") val tradeId: TradeId,
        @get:JvmName("reason") val reason: String?,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = tradeId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }
}
