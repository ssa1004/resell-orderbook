package com.example.market.application.service;

import com.example.market.application.command.CancelListingCommand;
import com.example.market.application.exception.ListingNotFoundException;
import com.example.market.application.port.in.CancelListingUseCase;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.ListingRepository;
import com.example.market.domain.trading.Listing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class CancelListingService implements CancelListingUseCase {

    private final ListingRepository listings;
    private final EventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public void cancel(CancelListingCommand cmd) {
        Listing listing = listings.findById(cmd.listingId())
                .orElseThrow(() -> new ListingNotFoundException(cmd.listingId()));
        // 도메인이 ownership invariant 보장 — 다른 사용자가 호출 시 ListingOwnershipViolation
        listing.cancel(cmd.requestor());
        listings.save(listing);
        Instant now = clock.instant();
        events.publish(listing.cancelled(now));
        log.info("listing cancelled id={} by={}", listing.id(), cmd.requestor());
    }
}
