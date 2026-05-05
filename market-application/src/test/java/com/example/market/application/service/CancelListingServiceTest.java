package com.example.market.application.service;

import com.example.market.application.command.CancelListingCommand;
import com.example.market.application.exception.ListingNotFoundException;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.ListingRepository;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.Listing;
import com.example.market.domain.trading.ListingId;
import com.example.market.domain.trading.ListingOwnershipViolation;
import com.example.market.domain.trading.ListingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CancelListingServiceTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");
    private static final UserId SELLER = UserId.of("seller");
    private static final UserId STRANGER = UserId.of("stranger");
    private static final SkuId SKU = SkuId.newId();

    private ListingRepository listings;
    private EventPublisher events;
    private CancelListingService service;

    @BeforeEach
    void setUp() {
        listings = mock(ListingRepository.class);
        events = mock(EventPublisher.class);
        service = new CancelListingService(listings, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void cancel_byOwner_setsStatusCancelled() {
        Listing listing = Listing.place(SKU, SELLER,
                Money.of(BigDecimal.valueOf(100_000), KRW), NOW);
        when(listings.findById(listing.id())).thenReturn(Optional.of(listing));

        service.cancel(new CancelListingCommand(SELLER, listing.id()));

        assertThat(listing.status()).isEqualTo(ListingStatus.CANCELLED);
        verify(listings).save(listing);
        verify(events).publish(any(Listing.ListingCancelled.class));
    }

    @Test
    void cancel_byStranger_throwsOwnershipViolationAndKeepsActive() {
        Listing listing = Listing.place(SKU, SELLER,
                Money.of(BigDecimal.valueOf(100_000), KRW), NOW);
        when(listings.findById(listing.id())).thenReturn(Optional.of(listing));

        assertThatThrownBy(() -> service.cancel(new CancelListingCommand(STRANGER, listing.id())))
                .isInstanceOf(ListingOwnershipViolation.class);

        // 도메인이 거부 → save 도 publish 도 호출 안됨
        assertThat(listing.status()).isEqualTo(ListingStatus.ACTIVE);
        verify(listings, never()).save(any());
        verify(events, never()).publish(any());
    }

    @Test
    void cancel_notFound_throws() {
        ListingId missing = ListingId.newId();
        when(listings.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(new CancelListingCommand(SELLER, missing)))
                .isInstanceOf(ListingNotFoundException.class);
    }
}
