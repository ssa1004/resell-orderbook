package com.example.market.domain.trading;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.settlement.FeePolicy;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeStateMachineTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");
    private static final SkuId SKU = SkuId.newId();
    private static final UserId BUYER = UserId.of("buyer");
    private static final UserId SELLER = UserId.of("seller");
    private static final FeePolicy POLICY = new FeePolicy(
            new BigDecimal("3.0"), new BigDecimal("3.5"),
            money(3_000), money(3_000), money(1_000));

    private Trade newTrade() {
        Listing ask = Listing.place(SKU, SELLER, money(140_000), NOW);
        Bid bid = Bid.place(SKU, BUYER, money(150_000), NOW);
        return Trade.match(ask, bid, money(150_000), POLICY, NOW);
    }

    @Test
    void happyPath_completesAfterInspectionAndShipping() {
        Trade t = newTrade();
        assertThat(t.status()).isEqualTo(TradeStatus.CREATED);
        assertThat(t.feeSnapshot().tradeAmount()).isEqualTo(money(150_000));

        t.authorizePayment("pg-tx-1", NOW);
        assertThat(t.status()).isEqualTo(TradeStatus.PAYMENT_AUTHORIZED);
        assertThat(t.pgPaymentId()).isEqualTo("pg-tx-1");

        t.startSellerShipping(NOW);
        assertThat(t.status()).isEqualTo(TradeStatus.SELLER_SHIPPING);

        t.arriveAtInspection(NOW);
        t.passInspection(NOW);
        t.startBuyerShipping(NOW);

        Trade.TradeCompleted ev = t.complete(NOW);
        assertThat(t.status()).isEqualTo(TradeStatus.COMPLETED);
        assertThat(ev.sellerId()).isEqualTo(SELLER);
        assertThat(ev.buyerId()).isEqualTo(BUYER);
        // 정산액 = 150,000 - 3% - 1,000 = 144,500
        assertThat(ev.sellerNet()).isEqualTo(money(144_500));
    }

    @Test
    void inspectionFails_branchesToRefundingThenFailed() {
        Trade t = newTrade();
        t.authorizePayment("pg-tx", NOW);
        t.startSellerShipping(NOW);
        t.arriveAtInspection(NOW);
        Trade.InspectionFailed ev = t.failInspection("fake item", NOW);
        assertThat(t.status()).isEqualTo(TradeStatus.INSPECTION_FAILED);
        assertThat(ev.reason()).isEqualTo("fake item");

        Trade.RefundingStarted refunding = t.startRefunding(NOW);
        assertThat(t.status()).isEqualTo(TradeStatus.REFUNDING);
        // 환불액 = buyerCharge (거래가 + 3.5% + 검수비 + 배송비) = 150,000 + 5,250 + 3,000 + 3,000 = 161,250
        assertThat(refunding.buyerCharge()).isEqualTo(money(161_250));

        t.closeAsFailedAfterRefund(NOW);
        assertThat(t.status()).isEqualTo(TradeStatus.FAILED);
        assertThat(t.status().isTerminal()).isTrue();
    }

    /** 보강 항목 1: PG authorize 실패 시 CREATED → FAILED 직행. */
    @Test
    void cancelOnPaymentFailure_movesCreatedToFailed() {
        Trade t = newTrade();
        Trade.PaymentRejected ev = t.cancelOnPaymentFailure("PG insufficient funds", NOW);

        assertThat(t.status()).isEqualTo(TradeStatus.FAILED);
        assertThat(ev.reason()).isEqualTo("PG insufficient funds");
        assertThat(t.status().isTerminal()).isTrue();
    }

    @Test
    void cancelOnPaymentFailure_onlyFromCreated() {
        Trade t = newTrade();
        t.authorizePayment("pg-tx", NOW);
        assertThatThrownBy(() -> t.cancelOnPaymentFailure("late", NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotSkipStates() {
        Trade t = newTrade();
        assertThatThrownBy(() -> t.startSellerShipping(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotPassInspectionTwice() {
        Trade t = newTrade();
        t.authorizePayment("pg", NOW);
        t.startSellerShipping(NOW);
        t.arriveAtInspection(NOW);
        t.passInspection(NOW);
        assertThatThrownBy(() -> t.passInspection(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void selfTradeRejected() {
        Listing ask = Listing.place(SKU, UserId.of("alice"), money(140_000), NOW);
        Bid bid = Bid.place(SKU, UserId.of("alice"), money(150_000), NOW);
        assertThatThrownBy(() -> Trade.match(ask, bid, money(150_000), POLICY, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("self-trade");
    }

    private static Money money(long won) {
        return Money.of(BigDecimal.valueOf(won), KRW);
    }
}
