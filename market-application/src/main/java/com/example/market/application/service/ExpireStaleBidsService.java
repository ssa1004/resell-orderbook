package com.example.market.application.service;

import com.example.market.application.port.in.ExpireStaleBidsUseCase;
import com.example.market.application.port.out.BidRepository;
import com.example.market.application.port.out.StaleBidScanner;
import com.example.market.domain.trading.Bid;
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
public class ExpireStaleBidsService implements ExpireStaleBidsUseCase {

    private final BidRepository bids;
    private final StaleBidScanner scanner;
    private final Clock clock;

    @Override
    @Transactional
    public int expireBatch(int batchSize) {
        Instant now = clock.instant();
        List<Bid> stale = scanner.findStaleActive(now, batchSize);
        for (Bid b : stale) {
            b.expire(now);
            bids.save(b);
        }
        if (!stale.isEmpty()) {
            log.info("expired {} bids", stale.size());
        }
        return stale.size();
    }
}
