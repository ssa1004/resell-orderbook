package com.example.market.domain.trading;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListingBidInvariantsTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");
    private static final SkuId SKU = SkuId.newId();
    private static final UserId SELLER = UserId.of("seller");
    private static final UserId BUYER = UserId.of("buyer");

    @Test
    void listing_zeroPrice_rejected() {
        assertThatThrownBy(() -> Listing.place(SKU, SELLER, Money.zero(KRW), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("askPrice");
    }

    @Test
    void listing_negativePrice_rejected() {
        assertThatThrownBy(() -> Listing.place(SKU, SELLER,
                Money.of(BigDecimal.valueOf(-100), KRW), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void userId_blankRejected() {
        assertThatThrownBy(() -> UserId.of("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserId.of(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void listing_expiresIn30Days() {
        Listing l = Listing.place(SKU, SELLER, Money.of(BigDecimal.valueOf(100_000), KRW), NOW);
        assertThat(l.expiresAt()).isEqualTo(NOW.plusSeconds(30L * 24 * 3600));
    }

    @Test
    void listing_canCancelOnlyWhenActive() {
        Listing l = Listing.place(SKU, SELLER, Money.of(BigDecimal.valueOf(100_000), KRW), NOW);
        l.cancel(SELLER);
        assertThat(l.status()).isEqualTo(ListingStatus.CANCELLED);
        assertThatThrownBy(() -> l.cancel(SELLER)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void listing_cannotMarkMatchedAfterCancel() {
        Listing l = Listing.place(SKU, SELLER, Money.of(BigDecimal.valueOf(100_000), KRW), NOW);
        l.cancel(SELLER);
        assertThatThrownBy(() -> l.markMatched(TradeId.newId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void listing_cancelByOtherUser_throwsOwnershipViolation() {
        // 보강 항목: 다른 사용자가 남의 Listing 을 취소 시도
        Listing l = Listing.place(SKU, SELLER, Money.of(BigDecimal.valueOf(100_000), KRW), NOW);
        UserId stranger = UserId.of("stranger");
        assertThatThrownBy(() -> l.cancel(stranger))
                .isInstanceOf(ListingOwnershipViolation.class)
                .hasMessageContaining("forbidden");
        // 상태는 변경되지 않아야 함
        assertThat(l.status()).isEqualTo(ListingStatus.ACTIVE);
    }

    @Test
    void listing_expireAfter30DaysWorks() {
        Listing l = Listing.place(SKU, SELLER, Money.of(BigDecimal.valueOf(100_000), KRW), NOW);
        l.expire(NOW.plusSeconds(31L * 24 * 3600));
        assertThat(l.status()).isEqualTo(ListingStatus.EXPIRED);
    }

    @Test
    void listing_expireBeforeExpiresAt_rejected() {
        Listing l = Listing.place(SKU, SELLER, Money.of(BigDecimal.valueOf(100_000), KRW), NOW);
        assertThatThrownBy(() -> l.expire(NOW.plusSeconds(60))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void isMatchableAt_falseAfterExpiresEvenIfStillActive() {
        // 보강 항목 2: status=ACTIVE 이지만 expiresAt 지난 시점은 매칭 불가
        Listing l = Listing.place(SKU, SELLER, Money.of(BigDecimal.valueOf(100_000), KRW), NOW);
        assertThat(l.isMatchableAt(NOW.plusSeconds(60))).isTrue();
        assertThat(l.isMatchableAt(NOW.plusSeconds(31L * 24 * 3600))).isFalse();
    }

    @Test
    void bid_invariantsMirrorListing() {
        Bid b = Bid.place(SKU, BUYER, Money.of(BigDecimal.valueOf(100_000), KRW), NOW);
        assertThat(b.status()).isEqualTo(BidStatus.ACTIVE);
        assertThat(b.isMatchableAt(NOW.plusSeconds(31L * 24 * 3600))).isFalse();
        assertThatThrownBy(() -> Bid.place(SKU, BUYER, Money.zero(KRW), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        b.cancel(BUYER);
        assertThat(b.status()).isEqualTo(BidStatus.CANCELLED);
    }

    @Test
    void bid_cancelByOtherUser_throwsOwnershipViolation() {
        Bid b = Bid.place(SKU, BUYER, Money.of(BigDecimal.valueOf(100_000), KRW), NOW);
        assertThatThrownBy(() -> b.cancel(UserId.of("stranger")))
                .isInstanceOf(BidOwnershipViolation.class);
        assertThat(b.status()).isEqualTo(BidStatus.ACTIVE);
    }
}
