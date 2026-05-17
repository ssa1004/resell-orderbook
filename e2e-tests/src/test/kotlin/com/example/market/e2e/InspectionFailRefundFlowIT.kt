package com.example.market.e2e

import com.example.market.MarketApplication
import com.example.market.application.command.AssignInspectorCommand
import com.example.market.application.command.AuthorizePaymentCommand
import com.example.market.application.command.BuyNowCommand
import com.example.market.application.command.PlaceListingCommand
import com.example.market.application.command.RecordInspectionArrivalCommand
import com.example.market.application.command.RecordInspectionResultCommand
import com.example.market.application.command.RecordSellerShippingCommand
import com.example.market.application.command.RegisterProductCommand
import com.example.market.application.port.`in`.AssignInspectorUseCase
import com.example.market.application.port.`in`.AuthorizePaymentUseCase
import com.example.market.application.port.`in`.BuyNowUseCase
import com.example.market.application.port.`in`.PlaceListingUseCase
import com.example.market.application.port.`in`.RecordInspectionArrivalUseCase
import com.example.market.application.port.`in`.RecordInspectionResultUseCase
import com.example.market.application.port.`in`.RecordSellerShippingUseCase
import com.example.market.application.port.`in`.RefundBuyerUseCase
import com.example.market.application.port.`in`.RegisterProductUseCase
import com.example.market.application.port.out.RefundRepository
import com.example.market.application.port.out.SkuRepository
import com.example.market.application.port.out.TradeRepository
import com.example.market.domain.catalog.ProductCategory
import com.example.market.domain.catalog.Sku
import com.example.market.domain.inspection.InspectionOutcome
import com.example.market.domain.settlement.RefundStatus
import com.example.market.domain.shared.Money
import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.TradeStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

/**
 * 검수 FAIL → 환불 흐름. RefundBuyerUseCase 가 PG.refund 호출 + Trade 종착.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = [MarketApplication::class])
@ActiveProfiles("it")
class InspectionFailRefundFlowIT : E2ECleanupSupport() {

    @Autowired lateinit var registerProduct: RegisterProductUseCase
    @Autowired lateinit var skus: SkuRepository
    @Autowired lateinit var placeListing: PlaceListingUseCase
    @Autowired lateinit var buyNow: BuyNowUseCase
    @Autowired lateinit var authorizePayment: AuthorizePaymentUseCase
    @Autowired lateinit var recordSellerShipping: RecordSellerShippingUseCase
    @Autowired lateinit var recordInspectionArrival: RecordInspectionArrivalUseCase
    @Autowired lateinit var assignInspector: AssignInspectorUseCase
    @Autowired lateinit var recordInspectionResult: RecordInspectionResultUseCase
    @Autowired lateinit var refundBuyer: RefundBuyerUseCase
    @Autowired lateinit var trades: TradeRepository
    @Autowired lateinit var refunds: RefundRepository

    @Test
    fun inspectionFail_triggersRefund_thenTradeCloses() {
        val product = registerProduct.register(
            RegisterProductCommand(
                "Rolex", "Submariner", "126610LN",
                ProductCategory.WATCH, Instant.parse("2020-09-01T00:00:00Z"), null,
            ),
        )
        val sku = Sku.create(product.id, "ONE", null)
        skus.save(sku)

        val seller = UserId.of("seller-2")
        val buyer = UserId.of("buyer-2")
        val inspector = UserId.of("inspector-99")

        placeListing.place(PlaceListingCommand("ask-fail", seller, sku.id, money(15_000_000)))
        val trade = buyNow.buyNow(BuyNowCommand("buy-fail", buyer, sku.id))
        authorizePayment.authorize(AuthorizePaymentCommand(trade.id))
        recordSellerShipping.recordShipping(RecordSellerShippingCommand(seller, trade.id, "TRACK"))

        val inspection = recordInspectionArrival.arrive(
            RecordInspectionArrivalCommand(trade.id),
        )
        assignInspector.assign(AssignInspectorCommand(inspection.id, inspector))

        // 검수 FAIL — 가짜 시리얼
        recordInspectionResult.record(
            RecordInspectionResultCommand(
                inspector, inspection.id,
                InspectionOutcome.FAIL, "fake serial", "engraving mismatch", emptyList(),
            ),
        )

        val afterFail = trades.findById(trade.id).orElseThrow()
        assertThat(afterFail.status).isEqualTo(TradeStatus.INSPECTION_FAILED)

        // RefundBuyer (자동 — 컨슈머 호출 시뮬)
        val refund = refundBuyer.refund(trade.id)
        assertThat(refund.status).isEqualTo(RefundStatus.COMPLETED)
        // 환불액 = buyerCharge — 검수비/배송비 포함
        assertThat(refund.amount.amount).isEqualByComparingTo(
            trade.feeSnapshot.buyerCharge.amount,
        )

        val afterRefund = trades.findById(trade.id).orElseThrow()
        assertThat(afterRefund.status).isEqualTo(TradeStatus.FAILED)

        // 영속 검증
        assertThat(refunds.findByTradeId(trade.id)).isPresent
    }

    @Test
    fun duplicateRefundCall_isIdempotent_returnsExisting() {
        val product = registerProduct.register(
            RegisterProductCommand(
                "Adidas", "Yeezy 350", "BB1826",
                ProductCategory.SNEAKERS, Instant.parse("2016-09-15T00:00:00Z"), null,
            ),
        )
        val sku = Sku.create(product.id, "265", null)
        skus.save(sku)

        val seller = UserId.of("seller-3")
        val buyer = UserId.of("buyer-3")
        val inspector = UserId.of("inspector-x")

        placeListing.place(PlaceListingCommand("ask-dup", seller, sku.id, money(500_000)))
        val trade = buyNow.buyNow(BuyNowCommand("buy-dup", buyer, sku.id))
        authorizePayment.authorize(AuthorizePaymentCommand(trade.id))
        recordSellerShipping.recordShipping(RecordSellerShippingCommand(seller, trade.id, "T"))
        val inspection = recordInspectionArrival.arrive(RecordInspectionArrivalCommand(trade.id))
        assignInspector.assign(AssignInspectorCommand(inspection.id, inspector))
        recordInspectionResult.record(
            RecordInspectionResultCommand(
                inspector, inspection.id,
                InspectionOutcome.FAIL, "wrong size", "270 instead of 265", emptyList(),
            ),
        )

        val first = refundBuyer.refund(trade.id)
        val second = refundBuyer.refund(trade.id)

        assertThat(second.id).isEqualTo(first.id) // 같은 Refund 반환 (idempotent)
        assertThat(refunds.findByTradeId(trade.id)).isPresent
    }

    companion object {
        private val KRW: Currency = Currency.getInstance("KRW")

        @Container
        @ServiceConnection
        @JvmField
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")

        private fun money(won: Long): Money = Money.of(BigDecimal.valueOf(won), KRW)
    }
}
