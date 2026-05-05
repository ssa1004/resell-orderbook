package com.example.market.application.service;

import com.example.market.application.port.in.ExpireStaleListingsUseCase;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.ListingRepository;
import com.example.market.application.port.out.StaleListingScanner;
import com.example.market.domain.trading.Listing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExpireStaleListingsService implements ExpireStaleListingsUseCase {

    private final ListingRepository listings;
    private final StaleListingScanner scanner;
    private final EventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public int expireBatch(int batchSize) {
        Instant now = clock.instant();
        List<Listing> stale = scanner.findStaleActive(now, batchSize);
        for (Listing l : stale) {
            l.expire(now);
            listings.save(l);
        }
        if (!stale.isEmpty()) {
            log.info("expired {} listings", stale.size());
        }
        return stale.size();
    }
}
