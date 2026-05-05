package com.example.market.e2e;

import com.example.market.MarketApplication;
import com.example.market.adapter.out.persistence.outbox.OutboxRepository;
import com.example.market.application.command.PlaceBidCommand;
import com.example.market.application.command.PlaceListingCommand;
import com.example.market.application.command.RegisterProductCommand;
import com.example.market.application.port.in.OrderBookQueryUseCase;
import com.example.market.application.port.in.PlaceBidUseCase;
import com.example.market.application.port.in.PlaceListingUseCase;
import com.example.market.application.port.in.RegisterProductUseCase;
import com.example.market.application.port.out.SkuRepository;
import com.example.market.domain.catalog.ProductCategory;
import com.example.market.domain.catalog.Sku;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trading 매칭 흐름 e2e — 실제 Postgres 위에서 BID/ASK 매칭 검증.
 *
 * <p>시나리오:</p>
 * <ol>
 *   <li>Product + Sku 등록</li>
 *   <li>BID 등록 (호가창에 들어감, no match)</li>
 *   <li>BID 보다 낮은 ASK 등록 → 즉시 매칭 (가격 = BID 가격, BID 가 maker)</li>
 *   <li>OrderBook query 로 매칭 후 BID 사라진 것 확인</li>
 *   <li>Outbox 에 ListingPlaced/BidPlaced/TradeMatched 이벤트 INSERT 확인</li>
 * </ol>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = MarketApplication.class)
@ActiveProfiles("it")
class TradingMatchFlowIT {

    private static final Currency KRW = Currency.getInstance("KRW");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired RegisterProductUseCase registerProduct;
    @Autowired SkuRepository skus;
    @Autowired PlaceListingUseCase placeListing;
    @Autowired PlaceBidUseCase placeBid;
    @Autowired OrderBookQueryUseCase orderBookQuery;
    @Autowired OutboxRepository outbox;

    private com.example.market.domain.catalog.SkuId skuId;

    @BeforeEach
    void setUp() {
        var product = registerProduct.register(new RegisterProductCommand(
                "Nike", "Air Jordan 1 Chicago", "555088-101",
                ProductCategory.SNEAKERS, Instant.parse("2015-05-30T00:00:00Z"), null));
        var sku = Sku.create(product.id(), "270", "Black");
        skus.save(sku);
        skuId = sku.id();
    }

    @Test
    void bid_then_lowerAsk_matchesAtBidPrice() {
        // 1. BID 등록 (160,000) — 매칭 안 됨 (호가창에 들어감)
        var bidResult = placeBid.place(new PlaceBidCommand(
                "bid-1", UserId.of("buyer-1"), skuId, money(160_000)));
        assertThat(bidResult.matchedTradeId()).isEmpty();

        var ob1 = orderBookQuery.view(skuId, 10);
        assertThat(ob1.highestBid()).isPresent().get().isEqualTo(money(160_000));
        assertThat(ob1.lowestAsk()).isEmpty();

        // 2. ASK 140,000 등록 → 즉시 매칭 (BID 가 maker, 가격=160,000)
        var askResult = placeListing.place(new PlaceListingCommand(
                "ask-1", UserId.of("seller-1"), skuId, money(140_000)));

        assertThat(askResult.matchedTradeId()).isPresent();

        // 3. 매칭 후 호가창은 비어있어야 함
        var ob2 = orderBookQuery.view(skuId, 10);
        assertThat(ob2.lowestAsk()).isEmpty();
        assertThat(ob2.highestBid()).isEmpty();

        // 4. Outbox 에 BidPlaced + TradeMatched 이벤트 (ListingPlaced 는 매칭됐으니 X)
        var events = outbox.findAll();
        assertThat(events).extracting(o -> o.getEventType())
                .contains("BidPlaced", "TradeMatched");
        assertThat(events).allSatisfy(o -> assertThat(o.getPublishedAt()).isNull());
    }

    @Test
    void higherAsk_doesNotMatch_bothStayInOrderBook() {
        placeBid.place(new PlaceBidCommand("bid-no", UserId.of("buyer-1"), skuId, money(140_000)));
        placeListing.place(new PlaceListingCommand("ask-no", UserId.of("seller-1"), skuId, money(160_000)));

        var ob = orderBookQuery.view(skuId, 10);
        assertThat(ob.highestBid()).isPresent().get().isEqualTo(money(140_000));
        assertThat(ob.lowestAsk()).isPresent().get().isEqualTo(money(160_000));

        // 매칭 X — Outbox 에 ListingPlaced + BidPlaced 둘 다, TradeMatched X
        var events = outbox.findAll();
        assertThat(events).extracting(o -> o.getEventType())
                .contains("ListingPlaced", "BidPlaced")
                .doesNotContain("TradeMatched");
    }

    @Test
    void duplicateIdempotencyKey_throws() {
        var cmd = new PlaceListingCommand("dup-key", UserId.of("seller"), skuId, money(140_000));
        placeListing.place(cmd);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> placeListing.place(cmd))
                .isInstanceOf(com.example.market.application.port.out.IdempotencyKeyStore.DuplicateRequestException.class);
    }

    private static Money money(long won) {
        return Money.of(BigDecimal.valueOf(won), KRW);
    }
}
