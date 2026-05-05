package com.example.market.domain.trading;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.shared.DomainEvent;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;

import java.time.Instant;
import java.util.Objects;

/**
 * Bid — 구매자가 등록한 *구매 호가*. "이 가격에 사겠다".
 * 매칭 우선순위: 가격 높은 순 → 시간 오래된 순.
 */
public class Bid {

    private final BidId id;
    private final SkuId skuId;
    private final UserId buyerId;
    private final Money bidPrice;
    private final Instant expiresAt;
    private final Instant createdAt;
    private BidStatus status;
    private TradeId matchedTradeId;
    private long version;

    private Bid(BidId id, SkuId skuId, UserId buyerId, Money bidPrice,
                Instant expiresAt, Instant createdAt, BidStatus status,
                TradeId matchedTradeId, long version) {
        this.id = id;
        this.skuId = skuId;
        this.buyerId = buyerId;
        this.bidPrice = bidPrice;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.status = status;
        this.matchedTradeId = matchedTradeId;
        this.version = version;
    }

    public static Bid place(SkuId skuId, UserId buyerId, Money bidPrice, Instant now) {
        Objects.requireNonNull(skuId, "skuId");
        Objects.requireNonNull(buyerId, "buyerId");
        Objects.requireNonNull(bidPrice, "bidPrice");
        if (!bidPrice.isPositive()) throw new IllegalArgumentException("bidPrice must be positive");
        Instant expiresAt = now.plusSeconds(30L * 24 * 3600);
        return new Bid(BidId.newId(), skuId, buyerId, bidPrice,
                expiresAt, now, BidStatus.ACTIVE, null, 0L);
    }

    public static Bid restore(BidId id, SkuId skuId, UserId buyerId, Money bidPrice,
                              Instant expiresAt, Instant createdAt, BidStatus status,
                              TradeId matchedTradeId, long version) {
        return new Bid(id, skuId, buyerId, bidPrice, expiresAt, createdAt,
                status, matchedTradeId, version);
    }

    public void markMatched(TradeId tradeId) {
        if (status != BidStatus.ACTIVE) {
            throw new IllegalStateException("bid must be ACTIVE to match, was " + status);
        }
        this.status = BidStatus.MATCHED;
        this.matchedTradeId = tradeId;
    }

    /**
     * 구매자 본인만 자신의 호가를 취소할 수 있다.
     *
     * @throws IllegalStateException ACTIVE 가 아니면
     * @throws BidOwnershipViolation requestor 가 buyerId 와 다르면
     */
    public void cancel(UserId requestor) {
        Objects.requireNonNull(requestor, "requestor");
        if (!buyerId.equals(requestor)) {
            throw new BidOwnershipViolation(id, buyerId, requestor);
        }
        if (status != BidStatus.ACTIVE) {
            throw new IllegalStateException("bid must be ACTIVE to cancel, was " + status);
        }
        this.status = BidStatus.CANCELLED;
    }

    public void expire(Instant now) {
        if (status != BidStatus.ACTIVE) return;
        if (now.isBefore(expiresAt)) {
            throw new IllegalStateException("not yet expired: " + expiresAt);
        }
        this.status = BidStatus.EXPIRED;
    }

    public boolean isActive() { return status == BidStatus.ACTIVE; }

    public boolean isMatchableAt(Instant now) {
        return isActive() && now.isBefore(expiresAt);
    }

    public BidId id() { return id; }
    public SkuId skuId() { return skuId; }
    public UserId buyerId() { return buyerId; }
    public Money bidPrice() { return bidPrice; }
    public Instant expiresAt() { return expiresAt; }
    public Instant createdAt() { return createdAt; }
    public BidStatus status() { return status; }
    public TradeId matchedTradeId() { return matchedTradeId; }
    public long version() { return version; }

    public BidPlaced placed(Instant now) {
        return new BidPlaced(id, skuId, buyerId, bidPrice, now);
    }

    public BidCancelled cancelled(Instant now) {
        return new BidCancelled(id, skuId, now);
    }

    public record BidPlaced(BidId bidId, SkuId skuId, UserId buyerId,
                            Money bidPrice, Instant occurredAt) implements DomainEvent {
        @Override public String aggregateId() { return bidId.toString(); }
    }

    public record BidCancelled(BidId bidId, SkuId skuId, Instant occurredAt) implements DomainEvent {
        @Override public String aggregateId() { return bidId.toString(); }
    }
}
