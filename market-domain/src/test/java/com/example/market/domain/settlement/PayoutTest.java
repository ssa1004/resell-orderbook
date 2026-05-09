package com.example.market.domain.settlement;

import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.TradeId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");
    private static final UserId SELLER = UserId.of("seller-1");

    private final FeePolicy STANDARD_POLICY = new FeePolicy(
            new BigDecimal("3.0"),                       // sellerCommissionRate
            new BigDecimal("3.5"),                       // buyerCommissionRate
            Money.of(BigDecimal.valueOf(3_000), KRW),    // inspectionFee
            Money.of(BigDecimal.valueOf(3_000), KRW),    // shippingFee
            Money.of(BigDecimal.valueOf(1_000), KRW)     // processingFee
    );

    @Test
    void scheduleFromSnapshot_calculatesNetCorrectly() {
        FeeSnapshot snap = STANDARD_POLICY.snapshotFor(money(150_000));
        Payout p = Payout.schedule(TradeId.newId(), SELLER, snap, NOW);

        // 150,000 * 3% = 4,500 sellerCommission
        assertThat(p.sellerCommission().amount()).isEqualByComparingTo("4500");
        assertThat(p.processingFee().amount()).isEqualByComparingTo("1000");
        // sellerNet = 150,000 - 4,500 - 1,000 = 144,500
        assertThat(p.netAmount().amount()).isEqualByComparingTo("144500");
        assertThat(p.status()).isEqualTo(PayoutStatus.SCHEDULED);
    }

    @Test
    void feeSnapshot_buyerChargeIsTradeAmountPlusBuyerFeesAndAddons() {
        FeeSnapshot snap = STANDARD_POLICY.snapshotFor(money(150_000));
        // buyerCharge = 150,000 + 5,250 + 3,000 + 3,000 = 161,250
        assertThat(snap.buyerCommission().amount()).isEqualByComparingTo("5250");
        assertThat(snap.buyerCharge().amount()).isEqualByComparingTo("161250");
    }

    @Test
    void feeSnapshot_platformRevenue_isAllFeesMinusProcessing() {
        FeeSnapshot snap = STANDARD_POLICY.snapshotFor(money(150_000));
        // 5,250 + 4,500 + 3,000 + 3,000 - 1,000 = 14,750
        assertThat(snap.platformRevenue().amount()).isEqualByComparingTo("14750");
    }

    @Test
    void schedule_tooSmallToCoverSellerFees_rejectedAtSnapshot() {
        // 100원 거래 → seller commission(0) + processingFee(1000) = sellerNet 음수
        assertThatThrownBy(() -> STANDARD_POLICY.snapshotFor(money(100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sellerNet negative");
    }

    @Test
    void send_thenComplete_happyPath() {
        FeeSnapshot snap = STANDARD_POLICY.snapshotFor(money(150_000));
        Payout p = Payout.schedule(TradeId.newId(), SELLER, snap, NOW);
        var sent = p.send("bank-tx-1", NOW);
        assertThat(p.status()).isEqualTo(PayoutStatus.SENT);
        assertThat(sent.bankTransferId()).isEqualTo("bank-tx-1");

        var completed = p.complete(NOW.plusSeconds(60));
        assertThat(p.status()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(completed.netAmount().amount()).isEqualByComparingTo("144500");
    }

    @Test
    void cannotCompleteBeforeSend() {
        FeeSnapshot snap = STANDARD_POLICY.snapshotFor(money(150_000));
        Payout p = Payout.schedule(TradeId.newId(), SELLER, snap, NOW);
        assertThatThrownBy(() -> p.complete(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fail_canHappenFromAnyNonTerminalState() {
        FeeSnapshot snap = STANDARD_POLICY.snapshotFor(money(150_000));
        Payout p = Payout.schedule(TradeId.newId(), SELLER, snap, NOW);
        p.fail("bank rejected", NOW);
        assertThat(p.status()).isEqualTo(PayoutStatus.FAILED);
    }

    @Test
    void feePolicyRejectsExtremeRates() {
        assertThatThrownBy(() -> new FeePolicy(new BigDecimal("60"), BigDecimal.ZERO,
                Money.zero(KRW), Money.zero(KRW), Money.zero(KRW)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeePolicy(BigDecimal.ZERO, new BigDecimal("-1"),
                Money.zero(KRW), Money.zero(KRW), Money.zero(KRW)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void feePolicyRequiresAllFeesSameCurrency() {
        Currency USD = Currency.getInstance("USD");
        assertThatThrownBy(() -> new FeePolicy(BigDecimal.ZERO, BigDecimal.ZERO,
                Money.zero(KRW), Money.zero(USD), Money.zero(KRW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("share currency");
    }

    private static Money money(long won) {
        return Money.of(BigDecimal.valueOf(won), KRW);
    }
}
