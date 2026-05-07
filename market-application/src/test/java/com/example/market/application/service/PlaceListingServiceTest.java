package com.example.market.application.service;

import com.example.market.application.command.PlaceListingCommand;
import com.example.market.application.port.in.PlaceListingUseCase.PlaceListingResult;
import com.example.market.application.port.out.BidRepository;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.FeePolicyProvider;
import com.example.market.application.port.out.IdempotencyKeyStore;
import com.example.market.application.port.out.IdempotencyKeyStore.DuplicateRequestException;
import com.example.market.application.port.out.ListingRepository;
import com.example.market.application.port.out.OrderBookQueryPort;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.settlement.FeePolicy;
import com.example.market.domain.shared.DomainEvent;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.Bid;
import com.example.market.domain.trading.Listing;
import com.example.market.domain.trading.ListingStatus;
import com.example.market.domain.trading.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlaceListingService 의 매칭/non-matching 시나리오 + 동시성 제어 호출 순서 + idempotency.
 */
class PlaceListingServiceTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final SkuId SKU = SkuId.newId();
    private static final UserId SELLER = UserId.of("seller-1");
    private static final UserId BUYER = UserId.of("buyer-1");
    private static final FeePolicy POLICY = new FeePolicy(
            new BigDecimal("3.0"), new BigDecimal("3.5"),
            money(3_000), money(3_000), money(1_000));

    private ListingRepository listings;
    private BidRepository bids;
    private TradeRepository trades;
    private OrderBookQueryPort orderBook;
    private EventPublisher events;
    private IdempotencyKeyStore idempotency;
    private FeePolicyProvider feeProvider;
    private com.example.market.application.port.out.PriceTickRepository priceTicks;
    private PlaceListingService service;

    @BeforeEach
    void setUp() {
        listings = mock(ListingRepository.class);
        bids = mock(BidRepository.class);
        trades = mock(TradeRepository.class);
        orderBook = mock(OrderBookQueryPort.class);
        events = mock(EventPublisher.class);
        idempotency = mock(IdempotencyKeyStore.class);
        feeProvider = mock(FeePolicyProvider.class);
        priceTicks = mock(com.example.market.application.port.out.PriceTickRepository.class);
        when(feeProvider.current()).thenReturn(POLICY);
        service = new PlaceListingService(listings, bids, trades, orderBook,
                events, idempotency, feeProvider, priceTicks, CLOCK);
    }

    @Test
    void place_noMatchingBid_savesListingAndPublishesPlacedEvent() {
        when(orderBook.findHighestBidForUpdate(eq(SKU), any())).thenReturn(Optional.empty());

        PlaceListingResult result = service.place(new PlaceListingCommand(
                "key-1", SELLER, SKU, money(150_000)));

        assertThat(result.matchedTradeId()).isEmpty();

        // idempotency 점유 → advisory lock → save
        verify(idempotency).acquireOrThrow("key-1");
        verify(orderBook).acquireSkuLock(SKU);
        verify(listings, times(1)).save(any(Listing.class));
        verify(trades, never()).save(any());

        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(Listing.ListingPlaced.class);
    }

    @Test
    void place_matchingBidExists_createsTradeAndPublishesMatched() {
        Bid highestBid = Bid.place(SKU, BUYER, money(160_000), NOW);
        when(orderBook.findHighestBidForUpdate(eq(SKU), any())).thenReturn(Optional.of(highestBid));

        PlaceListingResult result = service.place(new PlaceListingCommand(
                "key-2", SELLER, SKU, money(140_000)));

        assertThat(result.matchedTradeId()).isPresent();

        // 매칭 시: listing+bid markMatched, trade INSERT
        verify(listings, times(2)).save(any(Listing.class));   // 처음 + markMatched 후
        verify(bids).save(highestBid);
        verify(trades).save(any(Trade.class));

        // TradeMatched 이벤트 — BID 가격으로 체결 (maker)
        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(Trade.TradeMatched.class);
        Trade.TradeMatched ev = (Trade.TradeMatched) captor.getValue();
        assertThat(ev.price()).isEqualTo(money(160_000));
    }

    @Test
    void place_duplicateKey_throwsAndDoesNotTouchAnything() {
        doThrow(new DuplicateRequestException("dup"))
                .when(idempotency).acquireOrThrow("dup");

        assertThatThrownBy(() -> service.place(new PlaceListingCommand(
                "dup", SELLER, SKU, money(100_000))))
                .isInstanceOf(DuplicateRequestException.class);

        verify(orderBook, never()).acquireSkuLock(any());
        verify(listings, never()).save(any());
        verify(events, never()).publish(any());
    }

    @Test
    void place_skuLockAcquiredBeforeBestBidQuery() {
        when(orderBook.findHighestBidForUpdate(eq(SKU), any())).thenReturn(Optional.empty());

        service.place(new PlaceListingCommand("key-3", SELLER, SKU, money(100_000)));

        var inOrder = org.mockito.Mockito.inOrder(orderBook);
        inOrder.verify(orderBook).acquireSkuLock(SKU);
        inOrder.verify(orderBook).findHighestBidForUpdate(eq(SKU), any());
    }

    @Test
    void place_listingMatched_listingStatusBecomesMATCHED() {
        Bid highestBid = Bid.place(SKU, BUYER, money(160_000), NOW);
        when(orderBook.findHighestBidForUpdate(eq(SKU), any())).thenReturn(Optional.of(highestBid));

        service.place(new PlaceListingCommand("key-4", SELLER, SKU, money(140_000)));

        ArgumentCaptor<Listing> listingCaptor = ArgumentCaptor.forClass(Listing.class);
        verify(listings, times(2)).save(listingCaptor.capture());
        Listing finalSave = listingCaptor.getAllValues().get(1);
        assertThat(finalSave.status()).isEqualTo(ListingStatus.MATCHED);
    }

    private static Money money(long won) {
        return Money.of(BigDecimal.valueOf(won), KRW);
    }
}
