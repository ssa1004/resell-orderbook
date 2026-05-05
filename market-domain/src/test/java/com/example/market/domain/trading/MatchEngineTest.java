package com.example.market.domain.trading;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.settlement.FeePolicy;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 매칭 엔진 — 가격 우선 / maker 가격 책정 / self-trade 방지 / Sku 일치 / 만료 호가 거름.
 */
class MatchEngineTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");
    private static final SkuId SKU = SkuId.newId();
    private static final UserId BUYER1 = UserId.of("buyer-1");
    private static final UserId SELLER1 = UserId.of("seller-1");
    private static final UserId ALICE = UserId.of("alice");

    private static final FeePolicy KREAM_LIKE = new FeePolicy(
            new BigDecimal("3.0"), new BigDecimal("3.5"),
            money(3_000), money(3_000), money(1_000));

    // ── 새 ASK vs 기존 BID ──────────────────────────────

    @Test
    void newAsk_meetsHigherBid_matchesAtBidPrice() {
        Bid existingBid = Bid.place(SKU, BUYER1, money(150_000), NOW);
        Listing newAsk = Listing.place(SKU, SELLER1, money(140_000), NOW);

        Optional<Trade> trade = MatchEngine.matchNewAsk(newAsk, Optional.of(existingBid), KREAM_LIKE, NOW);

        assertThat(trade).isPresent();
        // BID 가 maker 였으므로 가격 = BID 가격
        assertThat(trade.get().price()).isEqualTo(money(150_000));
        assertThat(trade.get().sellerId()).isEqualTo(SELLER1);
        assertThat(trade.get().buyerId()).isEqualTo(BUYER1);
        // FeeSnapshot 도 freeze 됐는지
        assertThat(trade.get().feeSnapshot().tradeAmount()).isEqualTo(money(150_000));
        assertThat(trade.get().feeSnapshot().sellerNet()).isEqualTo(money(150_000 - 4_500 - 1_000));
    }

    @Test
    void newAsk_meetsExactBidPrice_matches() {
        Bid existingBid = Bid.place(SKU, BUYER1, money(150_000), NOW);
        Listing newAsk = Listing.place(SKU, SELLER1, money(150_000), NOW);

        Optional<Trade> trade = MatchEngine.matchNewAsk(newAsk, Optional.of(existingBid), KREAM_LIKE, NOW);

        assertThat(trade).isPresent();
        assertThat(trade.get().price()).isEqualTo(money(150_000));
    }

    @Test
    void newAsk_higherThanBid_doesNotMatch() {
        Bid existingBid = Bid.place(SKU, BUYER1, money(150_000), NOW);
        Listing newAsk = Listing.place(SKU, SELLER1, money(160_000), NOW);

        assertThat(MatchEngine.matchNewAsk(newAsk, Optional.of(existingBid), KREAM_LIKE, NOW)).isEmpty();
    }

    @Test
    void newAsk_noBids_doesNotMatch() {
        Listing newAsk = Listing.place(SKU, SELLER1, money(140_000), NOW);
        assertThat(MatchEngine.matchNewAsk(newAsk, Optional.empty(), KREAM_LIKE, NOW)).isEmpty();
    }

    @Test
    void newAsk_selfTrade_doesNotMatch() {
        Bid existingBid = Bid.place(SKU, ALICE, money(150_000), NOW);
        Listing newAsk = Listing.place(SKU, ALICE, money(140_000), NOW);
        assertThat(MatchEngine.matchNewAsk(newAsk, Optional.of(existingBid), KREAM_LIKE, NOW)).isEmpty();
    }

    @Test
    void newAsk_skuMismatch_doesNotMatch() {
        Bid bid = Bid.place(SkuId.newId(), BUYER1, money(150_000), NOW);
        Listing ask = Listing.place(SkuId.newId(), SELLER1, money(140_000), NOW);
        assertThat(MatchEngine.matchNewAsk(ask, Optional.of(bid), KREAM_LIKE, NOW)).isEmpty();
    }

    // ── (보강 항목 2) 만료된 호가 거름 ──────────────────────────────

    @Test
    void newAsk_expiredBid_doesNotMatch_evenIfStillActiveStatus() {
        // BID 등록 시점은 한참 전, status 는 아직 ACTIVE (배치가 안 돈 시점)
        Instant longAgo = NOW.minusSeconds(31L * 24 * 3600); // 31일 전
        Bid expiredBid = Bid.place(SKU, BUYER1, money(150_000), longAgo);
        assertThat(expiredBid.status()).isEqualTo(BidStatus.ACTIVE);
        assertThat(expiredBid.isMatchableAt(NOW)).isFalse();  // 만료됐으므로 false

        Listing newAsk = Listing.place(SKU, SELLER1, money(140_000), NOW);
        assertThat(MatchEngine.matchNewAsk(newAsk, Optional.of(expiredBid), KREAM_LIKE, NOW)).isEmpty();
    }

    @Test
    void newBid_expiredAsk_doesNotMatch() {
        Instant longAgo = NOW.minusSeconds(31L * 24 * 3600);
        Listing expiredAsk = Listing.place(SKU, SELLER1, money(140_000), longAgo);
        Bid newBid = Bid.place(SKU, BUYER1, money(150_000), NOW);
        assertThat(MatchEngine.matchNewBid(newBid, Optional.of(expiredAsk), KREAM_LIKE, NOW)).isEmpty();
    }

    // ── 새 BID vs 기존 ASK ──────────────────────────────

    @Test
    void newBid_meetsLowerAsk_matchesAtAskPrice() {
        Listing existingAsk = Listing.place(SKU, SELLER1, money(140_000), NOW);
        Bid newBid = Bid.place(SKU, BUYER1, money(150_000), NOW);

        Optional<Trade> trade = MatchEngine.matchNewBid(newBid, Optional.of(existingAsk), KREAM_LIKE, NOW);

        assertThat(trade).isPresent();
        // ASK 가 maker 였으므로 가격 = ASK 가격 (구매자가 더 싸게 사게 됨)
        assertThat(trade.get().price()).isEqualTo(money(140_000));
    }

    @Test
    void newBid_lowerThanAsk_doesNotMatch() {
        Listing existingAsk = Listing.place(SKU, SELLER1, money(150_000), NOW);
        Bid newBid = Bid.place(SKU, BUYER1, money(140_000), NOW);
        assertThat(MatchEngine.matchNewBid(newBid, Optional.of(existingAsk), KREAM_LIKE, NOW)).isEmpty();
    }

    // ── BuyNow / SellNow ──────────────────────────────

    @Test
    void buyNow_matchesLowestAsk_atAskPrice() {
        Listing ask = Listing.place(SKU, SELLER1, money(140_000), NOW);
        Trade trade = MatchEngine.buyNow(ask, BUYER1, KREAM_LIKE, NOW);

        assertThat(trade.price()).isEqualTo(money(140_000));
        assertThat(trade.sellerId()).isEqualTo(SELLER1);
        assertThat(trade.buyerId()).isEqualTo(BUYER1);
        assertThat(trade.status()).isEqualTo(TradeStatus.CREATED);
    }

    @Test
    void buyNow_ownListing_throws() {
        Listing ask = Listing.place(SKU, ALICE, money(140_000), NOW);
        assertThatThrownBy(() -> MatchEngine.buyNow(ask, ALICE, KREAM_LIKE, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot buy own listing");
    }

    @Test
    void buyNow_nullAsk_throws() {
        assertThatThrownBy(() -> MatchEngine.buyNow(null, BUYER1, KREAM_LIKE, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sellNow_matchesHighestBid_atBidPrice() {
        Bid bid = Bid.place(SKU, BUYER1, money(150_000), NOW);
        Trade trade = MatchEngine.sellNow(bid, SELLER1, KREAM_LIKE, NOW);
        assertThat(trade.price()).isEqualTo(money(150_000));
    }

    private static Money money(long won) {
        return Money.of(BigDecimal.valueOf(won), KRW);
    }
}
