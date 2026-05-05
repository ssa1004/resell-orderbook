package com.example.market.application.service;

import com.example.market.application.command.CancelBidCommand;
import com.example.market.application.exception.BidNotFoundException;
import com.example.market.application.port.in.CancelBidUseCase;
import com.example.market.application.port.out.BidRepository;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.domain.trading.Bid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class CancelBidService implements CancelBidUseCase {

    private final BidRepository bids;
    private final EventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public void cancel(CancelBidCommand cmd) {
        Bid bid = bids.findById(cmd.bidId())
                .orElseThrow(() -> new BidNotFoundException(cmd.bidId()));
        bid.cancel(cmd.requestor());
        bids.save(bid);
        Instant now = clock.instant();
        events.publish(bid.cancelled(now));
        log.info("bid cancelled id={} by={}", bid.id(), cmd.requestor());
    }
}
