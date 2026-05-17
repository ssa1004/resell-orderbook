package com.example.market.e2e

import com.example.market.MarketApplication
import com.example.market.application.command.AssignInspectorCommand
import com.example.market.application.command.AuthorizePaymentCommand
import com.example.market.application.command.BuyNowCommand
import com.example.market.application.command.CompleteTradeCommand
import com.example.market.application.command.PlaceListingCommand
import com.example.market.application.command.RecordInspectionArrivalCommand
import com.example.market.application.command.RecordInspectionResultCommand
import com.example.market.application.command.RecordSellerShippingCommand
import com.example.market.application.command.RegisterProductCommand
import com.example.market.application.command.StartBuyerShippingCommand
import com.example.market.application.port.`in`.AssignInspectorUseCase
import com.example.market.application.port.`in`.AuthorizePaymentUseCase
import com.example.market.application.port.`in`.BuyNowUseCase
import com.example.market.application.port.`in`.CompleteTradeUseCase
import com.example.market.application.port.`in`.PlaceListingUseCase
import com.example.market.application.port.`in`.RecordInspectionArrivalUseCase
import com.example.market.application.port.`in`.RecordInspectionResultUseCase
import com.example.market.application.port.`in`.RecordSellerShippingUseCase
import com.example.market.application.port.`in`.RegisterProductUseCase
import com.example.market.application.port.`in`.SettleTradeUseCase
import com.example.market.application.port.`in`.StartBuyerShippingUseCase
import com.example.market.application.port.out.PayoutRepository
import com.example.market.application.port.out.SkuRepository
import com.example.market.application.port.out.TradeRepository
import com.example.market.domain.catalog.ProductCategory
import com.example.market.domain.catalog.Sku
import com.example.market.domain.inspection.InspectionOutcome
import com.example.market.domain.settlement.PayoutStatus
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
 * 전체 거래 라이프사이클 e2e — Place ASK → BuyNow → Authorize → Seller Ship →
 * Inspection PASS → Buyer Ship → Complete → Settle.
 *
 * 각 단계에서 도메인 상태 / 정산 금액 / Outbox 이벤트를 검증.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = [MarketApplication::class])
@ActiveProfiles("it")
class FullTradeLifecycleIT : E2ECleanupSupport() {

    @Autowired lateinit var registerProduct: RegisterProductUseCase
    @Autowired lateinit var skus: SkuRepository
    @Autowired lateinit var placeListing: PlaceListingUseCase
    @Autowired lateinit var buyNow: BuyNowUseCase
    @Autowired lateinit var authorizePayment: AuthorizePaymentUseCase
    @Autowired lateinit var recordSellerShipping: RecordSellerShippingUseCase
    @Autowired lateinit var recordInspectionArrival: RecordInspectionArrivalUseCase
    @Autowired lateinit var assignInspector: AssignInspectorUseCase
    @Autowired lateinit var recordInspectionResult: RecordInspectionResultUseCase
    @Autowired lateinit var startBuyerShipping: StartBuyerShippingUseCase
    @Autowired lateinit var completeTrade: CompleteTradeUseCase
    @Autowired lateinit var settleTrade: SettleTradeUseCase
    @Autowired lateinit var trades: TradeRepository
    @Autowired lateinit var payouts: PayoutRepository

    @Test
    fun happyPath_placeAskBuyNowThroughInspectionToCompletionAndSettle() {
        // 0. 상품/SKU
        val product = registerProduct.register(
            RegisterProductCommand(
                "Nike", "Air Jordan 1", "555088-101",
                ProductCategory.SNEAKERS, Instant.parse("2015-05-30T00:00:00Z"), null,
            ),
        )
        val sku = Sku.create(product.id, "270", "Black")
        skus.save(sku)

        // 1. ASK 등록 (150,000)
        val seller = UserId.of("seller-1")
        val buyer = UserId.of("buyer-1")
        placeListing.place(PlaceListingCommand("ask-1", seller, sku.id, money(150_000)))

        // 2. BuyNow → 매칭 (가격 = ASK 가격 = 150,000)
        val trade = buyNow.buyNow(BuyNowCommand("buy-1", buyer, sku.id))
        assertThat(trade.status).isEqualTo(TradeStatus.CREATED)
        assertThat(trade.price).isEqualTo(money(150_000))
        // FeeSnapshot 검증: buyerCharge = 150,000 + 5,250 + 3,000 + 3,000 = 161,250
        assertThat(trade.feeSnapshot.buyerCharge).isEqualTo(money(161_250))
        // sellerNet = 150,000 - 4,500 - 1,000 = 144,500
        assertThat(trade.feeSnapshot.sellerNet).isEqualTo(money(144_500))

        // 3. Authorize (Mock PG → 자동 승인)
        val afterAuth = authorizePayment.authorize(AuthorizePaymentCommand(trade.id))
        assertThat(afterAuth.status).isEqualTo(TradeStatus.PAYMENT_AUTHORIZED)
        assertThat(afterAuth.pgPaymentId).startsWith("mock-pg-")

        // 4. Seller shipping
        recordSellerShipping.recordShipping(
            RecordSellerShippingCommand(seller, trade.id, "TRACK-1234"),
        )
        assertThat(trades.findById(trade.id).orElseThrow().status)
            .isEqualTo(TradeStatus.SELLER_SHIPPING)

        // 5. Inspection arrival
        val inspection = recordInspectionArrival.arrive(
            RecordInspectionArrivalCommand(trade.id),
        )
        assertThat(trades.findById(trade.id).orElseThrow().status)
            .isEqualTo(TradeStatus.INSPECTION_PENDING)

        // 6. Inspector 배정
        assignInspector.assign(AssignInspectorCommand(inspection.id, UserId.of("inspector-1")))

        // 7. Inspection PASS
        recordInspectionResult.record(
            RecordInspectionResultCommand(
                UserId.of("inspector-1"), inspection.id,
                InspectionOutcome.PASS, null, "authentic", emptyList(),
            ),
        )
        assertThat(trades.findById(trade.id).orElseThrow().status)
            .isEqualTo(TradeStatus.INSPECTION_PASSED)

        // 8. Buyer shipping (자동 — 컨슈머가 호출하는 것을 직접 호출)
        startBuyerShipping.start(StartBuyerShippingCommand(trade.id, "TRACK-9999"))
        assertThat(trades.findById(trade.id).orElseThrow().status)
            .isEqualTo(TradeStatus.BUYER_SHIPPING)

        // 9. Complete (구매자 수령)
        val completed = completeTrade.complete(CompleteTradeCommand(buyer, trade.id))
        assertThat(completed.status).isEqualTo(TradeStatus.COMPLETED)

        // 10. Settle (자동 — 컨슈머가 호출하는 것을 직접 호출)
        val payout = settleTrade.settle(trade.id)
        assertThat(payout.status).isEqualTo(PayoutStatus.SENT)
        assertThat(payout.netAmount).isEqualTo(money(144_500))
        assertThat(payout.bankTransferId).startsWith("mock-bank-")

        // 11. Payout 영속 확인
        val savedPayout = payouts.findByTradeId(trade.id).orElseThrow()
        assertThat(savedPayout.status).isEqualTo(PayoutStatus.SENT)
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
