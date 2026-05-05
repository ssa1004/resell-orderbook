package com.example.market.application.service;

import com.example.market.application.port.in.AutoCancelStaleTradesUseCase;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.trading.Trade;
import com.example.market.domain.trading.TradeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * TTL (예: 15분) 지난 CREATED 거래 자동 cancelOnPaymentFailure.
 * 매칭 후 PG authorize 가 늦어진 거래를 정리.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutoCancelStaleTradesService implements AutoCancelStaleTradesUseCase {

    private final TradeRepository trades;
    private final EventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public int cancelStale(Duration ttl, int batchSize) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(ttl);
        List<Trade> stale = trades.findStaleCreated(cutoff);
        if (stale.isEmpty()) return 0;

        int cancelled = 0;
        for (Trade trade : stale.subList(0, Math.min(stale.size(), batchSize))) {
            if (trade.status() != TradeStatus.CREATED) continue; // safety
            var ev = trade.cancelOnPaymentFailure("PAYMENT_TTL_EXCEEDED", now);
            trades.save(trade);
            events.publish(ev);
            cancelled++;
        }
        log.info("auto-cancelled {} stale trades (TTL={}min)", cancelled, ttl.toMinutes());
        return cancelled;
    }
}
