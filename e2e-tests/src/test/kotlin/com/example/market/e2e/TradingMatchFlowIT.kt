package com.example.market.e2e

import com.example.market.MarketApplication
import com.example.market.adapter.out.persistence.outbox.OutboxRepository
import com.example.market.application.command.PlaceBidCommand
import com.example.market.application.command.PlaceListingCommand
import com.example.market.application.command.RegisterProductCommand
import com.example.market.application.port.`in`.OrderBookQueryUseCase
import com.example.market.application.port.`in`.PlaceBidUseCase
import com.example.market.application.port.`in`.PlaceListingUseCase
import com.example.market.application.port.`in`.RegisterProductUseCase
import com.example.market.application.port.out.IdempotencyKeyStore
import com.example.market.application.port.out.SkuRepository
import com.example.market.domain.catalog.ProductCategory
import com.example.market.domain.catalog.Sku
import com.example.market.domain.catalog.SkuId
import com.example.market.domain.shared.Money
import com.example.market.domain.shared.UserId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
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
 * Trading 매칭 흐름 e2e — 실제 Postgres 위에서 BID/ASK 매칭 검증.
 *
 * 시나리오:
 * 1. Product + Sku 등록
 * 2. BID 등록 (호가창에 들어감, no match)
 * 3. BID 보다 낮은 ASK 등록 → 즉시 매칭 (가격 = BID 가격, BID 가 maker)
 * 4. OrderBook query 로 매칭 후 BID 사라진 것 확인
 * 5. Outbox 에 ListingPlaced/BidPlaced/TradeMatched 이벤트 INSERT 확인
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = [MarketApplication::class])
@ActiveProfiles("it")
class TradingMatchFlowIT : E2ECleanupSupport() {

    @Autowired lateinit var registerProduct: RegisterProductUseCase
    @Autowired lateinit var skus: SkuRepository
    @Autowired lateinit var placeListing: PlaceListingUseCase
    @Autowired lateinit var placeBid: PlaceBidUseCase
    @Autowired lateinit var orderBookQuery: OrderBookQueryUseCase
    @Autowired lateinit var outbox: OutboxRepository

    private lateinit var skuId: SkuId

    @BeforeEach
    fun setUp() {
        val product = registerProduct.register(
            RegisterProductCommand(
                "Nike", "Air Jordan 1 Chicago", "555088-101",
                ProductCategory.SNEAKERS, Instant.parse("2015-05-30T00:00:00Z"), null,
            ),
        )
        val sku = Sku.create(product.id, "270", "Black")
        skus.save(sku)
        skuId = sku.id
    }

    @Test
    fun bid_then_lowerAsk_matchesAtBidPrice() {
        // 1. BID 등록 (160,000) — 매칭 안 됨 (호가창에 들어감)
        val bidResult = placeBid.place(
            PlaceBidCommand("bid-1", UserId.of("buyer-1"), skuId, money(160_000)),
        )
        assertThat(bidResult.matchedTradeId).isEmpty

        val ob1 = orderBookQuery.view(skuId, 10)
        assertThat(ob1.highestBid).isPresent.get().isEqualTo(money(160_000))
        assertThat(ob1.lowestAsk).isEmpty

        // 2. ASK 140,000 등록 → 즉시 매칭 (BID 가 maker, 가격=160,000)
        val askResult = placeListing.place(
            PlaceListingCommand("ask-1", UserId.of("seller-1"), skuId, money(140_000)),
        )

        assertThat(askResult.matchedTradeId).isPresent

        // 3. 매칭 후 호가창은 비어있어야 함
        val ob2 = orderBookQuery.view(skuId, 10)
        assertThat(ob2.lowestAsk).isEmpty
        assertThat(ob2.highestBid).isEmpty

        // 4. Outbox 에 BidPlaced + TradeMatched 이벤트 (ListingPlaced 는 매칭됐으니 X)
        val events = outbox.findAll()
        assertThat(events).extracting<String> { it.eventType }
            .contains("BidPlaced", "TradeMatched")
        assertThat(events).allSatisfy { assertThat(it.publishedAt).isNull() }
    }

    @Test
    fun higherAsk_doesNotMatch_bothStayInOrderBook() {
        placeBid.place(PlaceBidCommand("bid-no", UserId.of("buyer-1"), skuId, money(140_000)))
        placeListing.place(PlaceListingCommand("ask-no", UserId.of("seller-1"), skuId, money(160_000)))

        val ob = orderBookQuery.view(skuId, 10)
        assertThat(ob.highestBid).isPresent.get().isEqualTo(money(140_000))
        assertThat(ob.lowestAsk).isPresent.get().isEqualTo(money(160_000))

        // 매칭 X — Outbox 에 ListingPlaced + BidPlaced 둘 다, TradeMatched X
        val events = outbox.findAll()
        assertThat(events).extracting<String> { it.eventType }
            .contains("ListingPlaced", "BidPlaced")
            .doesNotContain("TradeMatched")
    }

    @Test
    fun duplicateIdempotencyKey_throws() {
        val cmd = PlaceListingCommand("dup-key", UserId.of("seller"), skuId, money(140_000))
        placeListing.place(cmd)
        assertThatThrownBy { placeListing.place(cmd) }
            .isInstanceOf(IdempotencyKeyStore.DuplicateRequestException::class.java)
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
