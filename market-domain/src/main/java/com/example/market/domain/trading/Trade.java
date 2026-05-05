package com.example.market.domain.trading;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.settlement.FeePolicy;
import com.example.market.domain.settlement.FeeSnapshot;
import com.example.market.domain.shared.DomainEvent;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;

import java.time.Instant;
import java.util.Objects;

/**
 * Trade — 매칭된 거래. Listing(ASK) + Bid 가 만나서 생성됨.
 *
 * <p>가격 결정: <strong>먼저 등록된 호가의 가격</strong> (taker/maker 모델). 호출자가 결정해서 넘김.</p>
 *
 * <p>매칭 시점에 {@link FeeSnapshot} 을 freeze — 정책이 나중에 바뀌어도 이 거래의 수수료는 불변.</p>
 *
 * <p>상태머신 ({@link TradeStatus}):</p>
 * <pre>
 *  CREATED ──cancelOnPaymentFailure──▶ FAILED
 *     │
 *     ▼
 *  PAYMENT_AUTHORIZED → SELLER_SHIPPING → INSPECTION_PENDING
 *     │
 *     ├─ INSPECTION_PASSED → BUYER_SHIPPING → COMPLETED
 *     └─ INSPECTION_FAILED → REFUNDING → FAILED
 * </pre>
 */
public class Trade {

    private final TradeId id;
    private final SkuId skuId;
    private final ListingId listingId;
    private final BidId bidId;
    private final UserId sellerId;
    private final UserId buyerId;
    private final Money price;            // 체결가
    private final FeeSnapshot feeSnapshot; // 수수료 freeze
    private TradeStatus status;
    private String pgPaymentId;
    private String inspectionFailReason;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private Trade(TradeId id, SkuId skuId, ListingId listingId, BidId bidId,
                  UserId sellerId, UserId buyerId, Money price, FeeSnapshot feeSnapshot,
                  TradeStatus status, String pgPaymentId, String inspectionFailReason,
                  Instant createdAt, Instant updatedAt, long version) {
        this.id = id;
        this.skuId = skuId;
        this.listingId = listingId;
        this.bidId = bidId;
        this.sellerId = sellerId;
        this.buyerId = buyerId;
        this.price = price;
        this.feeSnapshot = feeSnapshot;
        this.status = status;
        this.pgPaymentId = pgPaymentId;
        this.inspectionFailReason = inspectionFailReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    /** 매칭 시 Listing + Bid + 정책 → Trade 생성. 정책은 즉시 snapshot. */
    public static Trade match(Listing listing, Bid bid, Money executionPrice,
                              FeePolicy feePolicy, Instant now) {
        Objects.requireNonNull(listing); Objects.requireNonNull(bid);
        Objects.requireNonNull(feePolicy);
        if (!listing.skuId().equals(bid.skuId())) {
            throw new IllegalArgumentException("listing/bid Sku mismatch");
        }
        if (!listing.isActive()) throw new IllegalStateException("listing not ACTIVE: " + listing.status());
        if (!bid.isActive()) throw new IllegalStateException("bid not ACTIVE: " + bid.status());
        if (listing.sellerId().equals(bid.buyerId())) {
            throw new IllegalArgumentException("self-trade not allowed: " + listing.sellerId());
        }
        FeeSnapshot snap = feePolicy.snapshotFor(executionPrice);
        return new Trade(TradeId.newId(), listing.skuId(), listing.id(), bid.id(),
                listing.sellerId(), bid.buyerId(), executionPrice, snap,
                TradeStatus.CREATED, null, null, now, now, 0L);
    }

    public static Trade restore(TradeId id, SkuId skuId, ListingId listingId, BidId bidId,
                                UserId sellerId, UserId buyerId, Money price, FeeSnapshot feeSnapshot,
                                TradeStatus status, String pgPaymentId, String inspectionFailReason,
                                Instant createdAt, Instant updatedAt, long version) {
        return new Trade(id, skuId, listingId, bidId, sellerId, buyerId, price, feeSnapshot, status,
                pgPaymentId, inspectionFailReason, createdAt, updatedAt, version);
    }

    // ── 상태 전이 ──────────────────────────────────────

    public TradeMatched matched(Instant now) {
        return new TradeMatched(id, skuId, listingId, bidId, sellerId, buyerId, price,
                feeSnapshot.buyerCharge(), feeSnapshot.sellerNet(), now);
    }

    public PaymentAuthorized authorizePayment(String pgPaymentId, Instant now) {
        require(TradeStatus.CREATED, "authorizePayment");
        this.pgPaymentId = pgPaymentId;
        this.status = TradeStatus.PAYMENT_AUTHORIZED;
        this.updatedAt = now;
        return new PaymentAuthorized(id, pgPaymentId, feeSnapshot.buyerCharge(), now);
    }

    /** PG authorize 실패. CREATED → FAILED 직행. (보강 항목 1) */
    public PaymentRejected cancelOnPaymentFailure(String reason, Instant now) {
        require(TradeStatus.CREATED, "cancelOnPaymentFailure");
        this.status = TradeStatus.FAILED;
        this.updatedAt = now;
        return new PaymentRejected(id, reason, now);
    }

    public SellerShippingRequested startSellerShipping(Instant now) {
        require(TradeStatus.PAYMENT_AUTHORIZED, "startSellerShipping");
        this.status = TradeStatus.SELLER_SHIPPING;
        this.updatedAt = now;
        return new SellerShippingRequested(id, sellerId, now);
    }

    public InspectionRequested arriveAtInspection(Instant now) {
        require(TradeStatus.SELLER_SHIPPING, "arriveAtInspection");
        this.status = TradeStatus.INSPECTION_PENDING;
        this.updatedAt = now;
        return new InspectionRequested(id, skuId, now);
    }

    public InspectionPassed passInspection(Instant now) {
        require(TradeStatus.INSPECTION_PENDING, "passInspection");
        this.status = TradeStatus.INSPECTION_PASSED;
        this.updatedAt = now;
        return new InspectionPassed(id, now);
    }

    public InspectionFailed failInspection(String reason, Instant now) {
        require(TradeStatus.INSPECTION_PENDING, "failInspection");
        this.status = TradeStatus.INSPECTION_FAILED;
        this.inspectionFailReason = reason;
        this.updatedAt = now;
        return new InspectionFailed(id, reason, now);
    }

    public BuyerShippingStarted startBuyerShipping(Instant now) {
        require(TradeStatus.INSPECTION_PASSED, "startBuyerShipping");
        this.status = TradeStatus.BUYER_SHIPPING;
        this.updatedAt = now;
        return new BuyerShippingStarted(id, buyerId, now);
    }

    public TradeCompleted complete(Instant now) {
        require(TradeStatus.BUYER_SHIPPING, "complete");
        this.status = TradeStatus.COMPLETED;
        this.updatedAt = now;
        return new TradeCompleted(id, sellerId, buyerId, price, feeSnapshot.sellerNet(), now);
    }

    public RefundingStarted startRefunding(Instant now) {
        require(TradeStatus.INSPECTION_FAILED, "startRefunding");
        this.status = TradeStatus.REFUNDING;
        this.updatedAt = now;
        return new RefundingStarted(id, buyerId, feeSnapshot.buyerCharge(), inspectionFailReason, now);
    }

    /**
     * 환불 처리가 모두 완료된 후 거래를 종착(FAILED) 상태로 마감.
     *
     * <p>의미: REFUNDING 단계에서 PG 환불이 성공해 더 이상 진행할 작업이 없을 때 호출.
     * "거래 자체가 실패로 끝났다" 를 기록 — terminal 상태 진입.</p>
     */
    public TradeFailed closeAsFailedAfterRefund(Instant now) {
        if (status != TradeStatus.REFUNDING) {
            throw new IllegalStateException(
                    "closeAsFailedAfterRefund() requires REFUNDING, was " + status);
        }
        this.status = TradeStatus.FAILED;
        this.updatedAt = now;
        return new TradeFailed(id, inspectionFailReason, now);
    }

    private void require(TradeStatus expected, String op) {
        if (status != expected) {
            throw new IllegalStateException(op + "() requires " + expected + ", was " + status);
        }
    }

    // ── getters ──────────────────────────────────────

    public TradeId id() { return id; }
    public SkuId skuId() { return skuId; }
    public ListingId listingId() { return listingId; }
    public BidId bidId() { return bidId; }
    public UserId sellerId() { return sellerId; }
    public UserId buyerId() { return buyerId; }
    public Money price() { return price; }
    public FeeSnapshot feeSnapshot() { return feeSnapshot; }
    public TradeStatus status() { return status; }
    public String pgPaymentId() { return pgPaymentId; }
    public String inspectionFailReason() { return inspectionFailReason; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }

    // ── domain events ──────────────────────────────────────

    public record TradeMatched(TradeId tradeId, SkuId skuId, ListingId listingId, BidId bidId,
                               UserId sellerId, UserId buyerId, Money price,
                               Money buyerCharge, Money sellerNet, Instant occurredAt)
            implements DomainEvent {
        @Override public String aggregateId() { return tradeId.toString(); }
    }

    public record PaymentAuthorized(TradeId tradeId, String pgPaymentId, Money amount, Instant occurredAt)
            implements DomainEvent {
        @Override public String aggregateId() { return tradeId.toString(); }
    }

    public record PaymentRejected(TradeId tradeId, String reason, Instant occurredAt)
            implements DomainEvent {
        @Override public String aggregateId() { return tradeId.toString(); }
    }

    public record SellerShippingRequested(TradeId tradeId, UserId sellerId, Instant occurredAt)
            implements DomainEvent {
        @Override public String aggregateId() { return tradeId.toString(); }
    }

    public record InspectionRequested(TradeId tradeId, SkuId skuId, Instant occurredAt)
            implements DomainEvent {
        @Override public String aggregateId() { return tradeId.toString(); }
    }

    public record InspectionPassed(TradeId tradeId, Instant occurredAt) implements DomainEvent {
        @Override public String aggregateId() { return tradeId.toString(); }
    }

    public record InspectionFailed(TradeId tradeId, String reason, Instant occurredAt)
            implements DomainEvent {
        @Override public String aggregateId() { return tradeId.toString(); }
    }

    public record BuyerShippingStarted(TradeId tradeId, UserId buyerId, Instant occurredAt)
            implements DomainEvent {
        @Override public String aggregateId() { return tradeId.toString(); }
    }

    public record TradeCompleted(TradeId tradeId, UserId sellerId, UserId buyerId,
                                 Money price, Money sellerNet, Instant occurredAt)
            implements DomainEvent {
        @Override public String aggregateId() { return tradeId.toString(); }
    }

    public record RefundingStarted(TradeId tradeId, UserId buyerId, Money buyerCharge,
                                   String reason, Instant occurredAt) implements DomainEvent {
        @Override public String aggregateId() { return tradeId.toString(); }
    }

    public record TradeFailed(TradeId tradeId, String reason, Instant occurredAt)
            implements DomainEvent {
        @Override public String aggregateId() { return tradeId.toString(); }
    }
}
