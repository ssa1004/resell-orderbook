package com.example.market.e2e;

import com.example.market.MarketApplication;
import com.example.market.application.command.AssignInspectorCommand;
import com.example.market.application.command.AuthorizePaymentCommand;
import com.example.market.application.command.BuyNowCommand;
import com.example.market.application.command.PlaceListingCommand;
import com.example.market.application.command.RecordInspectionArrivalCommand;
import com.example.market.application.command.RecordInspectionResultCommand;
import com.example.market.application.command.RecordSellerShippingCommand;
import com.example.market.application.command.RegisterProductCommand;
import com.example.market.application.port.in.AssignInspectorUseCase;
import com.example.market.application.port.in.AuthorizePaymentUseCase;
import com.example.market.application.port.in.BuyNowUseCase;
import com.example.market.application.port.in.PlaceListingUseCase;
import com.example.market.application.port.in.RecordInspectionArrivalUseCase;
import com.example.market.application.port.in.RecordInspectionResultUseCase;
import com.example.market.application.port.in.RecordSellerShippingUseCase;
import com.example.market.application.port.in.RefundBuyerUseCase;
import com.example.market.application.port.in.RegisterProductUseCase;
import com.example.market.application.port.out.RefundRepository;
import com.example.market.application.port.out.SkuRepository;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.catalog.ProductCategory;
import com.example.market.domain.catalog.Sku;
import com.example.market.domain.inspection.InspectionOutcome;
import com.example.market.domain.settlement.RefundStatus;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.Trade;
import com.example.market.domain.trading.TradeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검수 FAIL → 환불 흐름. RefundBuyerUseCase 가 PG.refund 호출 + Trade 종착.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = MarketApplication.class)
@ActiveProfiles("it")
class InspectionFailRefundFlowIT {

    private static final Currency KRW = Currency.getInstance("KRW");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired RegisterProductUseCase registerProduct;
    @Autowired SkuRepository skus;
    @Autowired PlaceListingUseCase placeListing;
    @Autowired BuyNowUseCase buyNow;
    @Autowired AuthorizePaymentUseCase authorizePayment;
    @Autowired RecordSellerShippingUseCase recordSellerShipping;
    @Autowired RecordInspectionArrivalUseCase recordInspectionArrival;
    @Autowired AssignInspectorUseCase assignInspector;
    @Autowired RecordInspectionResultUseCase recordInspectionResult;
    @Autowired RefundBuyerUseCase refundBuyer;
    @Autowired TradeRepository trades;
    @Autowired RefundRepository refunds;

    @Test
    void inspectionFail_triggersRefund_thenTradeCloses() {
        var product = registerProduct.register(new RegisterProductCommand(
                "Rolex", "Submariner", "126610LN",
                ProductCategory.WATCH, Instant.parse("2020-09-01T00:00:00Z"), null));
        var sku = Sku.create(product.id(), "ONE", null);
        skus.save(sku);

        UserId seller = UserId.of("seller-2");
        UserId buyer = UserId.of("buyer-2");
        UserId inspector = UserId.of("inspector-99");

        placeListing.place(new PlaceListingCommand("ask-fail", seller, sku.id(), money(15_000_000)));
        Trade trade = buyNow.buyNow(new BuyNowCommand("buy-fail", buyer, sku.id()));
        authorizePayment.authorize(new AuthorizePaymentCommand(trade.id()));
        recordSellerShipping.recordShipping(new RecordSellerShippingCommand(seller, trade.id(), "TRACK"));

        var inspection = recordInspectionArrival.arrive(
                new RecordInspectionArrivalCommand(trade.id()));
        assignInspector.assign(new AssignInspectorCommand(inspection.id(), inspector));

        // 검수 FAIL — 가짜 시리얼
        recordInspectionResult.record(new RecordInspectionResultCommand(
                inspector, inspection.id(),
                InspectionOutcome.FAIL, "fake serial", "engraving mismatch", List.of()));

        var afterFail = trades.findById(trade.id()).orElseThrow();
        assertThat(afterFail.status()).isEqualTo(TradeStatus.INSPECTION_FAILED);

        // RefundBuyer (자동 — 컨슈머 호출 시뮬)
        var refund = refundBuyer.refund(trade.id());
        assertThat(refund.status()).isEqualTo(RefundStatus.COMPLETED);
        // 환불액 = buyerCharge — 검수비/배송비 포함
        assertThat(refund.amount().amount()).isEqualByComparingTo(
                trade.feeSnapshot().buyerCharge().amount());

        var afterRefund = trades.findById(trade.id()).orElseThrow();
        assertThat(afterRefund.status()).isEqualTo(TradeStatus.FAILED);

        // 영속 검증
        assertThat(refunds.findByTradeId(trade.id())).isPresent();
    }

    @Test
    void duplicateRefundCall_isIdempotent_returnsExisting() {
        var product = registerProduct.register(new RegisterProductCommand(
                "Adidas", "Yeezy 350", "BB1826",
                ProductCategory.SNEAKERS, Instant.parse("2016-09-15T00:00:00Z"), null));
        var sku = Sku.create(product.id(), "265", null);
        skus.save(sku);

        UserId seller = UserId.of("seller-3");
        UserId buyer = UserId.of("buyer-3");
        UserId inspector = UserId.of("inspector-x");

        placeListing.place(new PlaceListingCommand("ask-dup", seller, sku.id(), money(500_000)));
        Trade trade = buyNow.buyNow(new BuyNowCommand("buy-dup", buyer, sku.id()));
        authorizePayment.authorize(new AuthorizePaymentCommand(trade.id()));
        recordSellerShipping.recordShipping(new RecordSellerShippingCommand(seller, trade.id(), "T"));
        var inspection = recordInspectionArrival.arrive(new RecordInspectionArrivalCommand(trade.id()));
        assignInspector.assign(new AssignInspectorCommand(inspection.id(), inspector));
        recordInspectionResult.record(new RecordInspectionResultCommand(
                inspector, inspection.id(),
                InspectionOutcome.FAIL, "wrong size", "270 instead of 265", List.of()));

        var first = refundBuyer.refund(trade.id());
        var second = refundBuyer.refund(trade.id());

        assertThat(second.id()).isEqualTo(first.id());      // 같은 Refund 반환 (idempotent)
        assertThat(refunds.findByTradeId(trade.id())).isPresent();
    }

    private static Money money(long won) {
        return Money.of(BigDecimal.valueOf(won), KRW);
    }
}
