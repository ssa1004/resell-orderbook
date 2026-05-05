package com.example.market.domain.trading;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.shared.DomainEvent;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;

import java.time.Instant;
import java.util.Objects;

/**
 * Listing(ASK) — 판매자가 등록한 *판매 호가*. "이 가격에 팔겠다".
 *
 * <p>상태: ACTIVE → MATCHED (체결) / CANCELLED / EXPIRED.<br>
 * 매칭 우선순위: 가격 낮은 순 → 시간 오래된 순.</p>
 */
public class Listing {

    private final ListingId id;
    private final SkuId skuId;
    private final UserId sellerId;
    private final Money askPrice;
    private final Instant expiresAt;
    private final Instant createdAt;
    private ListingStatus status;
    private TradeId matchedTradeId;
    private long version;

    private Listing(ListingId id, SkuId skuId, UserId sellerId, Money askPrice,
                    Instant expiresAt, Instant createdAt, ListingStatus status,
                    TradeId matchedTradeId, long version) {
        this.id = id;
        this.skuId = skuId;
        this.sellerId = sellerId;
        this.askPrice = askPrice;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.status = status;
        this.matchedTradeId = matchedTradeId;
        this.version = version;
    }

    /** 판매 호가 등록. 가격은 양수, 만료는 30일 기본. */
    public static Listing place(SkuId skuId, UserId sellerId, Money askPrice, Instant now) {
        Objects.requireNonNull(skuId, "skuId");
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(askPrice, "askPrice");
        if (!askPrice.isPositive()) throw new IllegalArgumentException("askPrice must be positive");
        Instant expiresAt = now.plusSeconds(30L * 24 * 3600);
        return new Listing(ListingId.newId(), skuId, sellerId, askPrice,
                expiresAt, now, ListingStatus.ACTIVE, null, 0L);
    }

    public static Listing restore(ListingId id, SkuId skuId, UserId sellerId, Money askPrice,
                                  Instant expiresAt, Instant createdAt, ListingStatus status,
                                  TradeId matchedTradeId, long version) {
        return new Listing(id, skuId, sellerId, askPrice, expiresAt, createdAt,
                status, matchedTradeId, version);
    }

    public void markMatched(TradeId tradeId) {
        if (status != ListingStatus.ACTIVE) {
            throw new IllegalStateException("listing must be ACTIVE to match, was " + status);
        }
        this.status = ListingStatus.MATCHED;
        this.matchedTradeId = tradeId;
    }

    /**
     * 판매자 본인만 자신의 호가를 취소할 수 있다.
     *
     * @throws IllegalStateException ACTIVE 가 아니면
     * @throws ListingOwnershipViolation requestor 가 sellerId 와 다르면
     */
    public void cancel(UserId requestor) {
        Objects.requireNonNull(requestor, "requestor");
        if (!sellerId.equals(requestor)) {
            throw new ListingOwnershipViolation(id, sellerId, requestor);
        }
        if (status != ListingStatus.ACTIVE) {
            throw new IllegalStateException("listing must be ACTIVE to cancel, was " + status);
        }
        this.status = ListingStatus.CANCELLED;
    }

    public void expire(Instant now) {
        if (status != ListingStatus.ACTIVE) return;  // idempotent
        if (now.isBefore(expiresAt)) {
            throw new IllegalStateException("not yet expired: " + expiresAt);
        }
        this.status = ListingStatus.EXPIRED;
    }

    public boolean isActive() { return status == ListingStatus.ACTIVE; }

    /** 매칭 가능 여부 — ACTIVE 이고 아직 만료되지 않음. MatchEngine 이 사용. */
    public boolean isMatchableAt(Instant now) {
        return isActive() && now.isBefore(expiresAt);
    }

    public ListingId id() { return id; }
    public SkuId skuId() { return skuId; }
    public UserId sellerId() { return sellerId; }
    public Money askPrice() { return askPrice; }
    public Instant expiresAt() { return expiresAt; }
    public Instant createdAt() { return createdAt; }
    public ListingStatus status() { return status; }
    public TradeId matchedTradeId() { return matchedTradeId; }
    public long version() { return version; }

    /** 호가창 broadcast 용 (매칭 X 일 때 발행). */
    public ListingPlaced placed(Instant now) {
        return new ListingPlaced(id, skuId, sellerId, askPrice, now);
    }

    /** 취소 broadcast 용. */
    public ListingCancelled cancelled(Instant now) {
        return new ListingCancelled(id, skuId, now);
    }

    public record ListingPlaced(ListingId listingId, SkuId skuId, UserId sellerId,
                                Money askPrice, Instant occurredAt) implements DomainEvent {
        @Override public String aggregateId() { return listingId.toString(); }
    }

    public record ListingCancelled(ListingId listingId, SkuId skuId, Instant occurredAt)
            implements DomainEvent {
        @Override public String aggregateId() { return listingId.toString(); }
    }
}
