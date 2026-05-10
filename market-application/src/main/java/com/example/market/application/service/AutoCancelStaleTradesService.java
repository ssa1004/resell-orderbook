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
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
        Instant now = clock.instant();
        Instant cutoff = now.minus(ttl);
        // batchSize 만큼만 fetch — 이전엔 repository 가 200 hard-coded 였어서 caller 의 hint
        // 가 무시됐다 (500/1000 호출해도 200 row 만 처리). 이제 호출자의 처리 단위와
        // fetch 단위가 일치.
        List<Trade> stale = trades.findStaleCreated(cutoff, batchSize);
        if (stale.isEmpty()) return 0;

        int cancelled = 0;
        for (Trade trade : stale) {
            if (trade.status() != TradeStatus.CREATED) continue; // safety
            var ev = trade.cancelOnPaymentFailure("PAYMENT_TTL_EXCEEDED", now);
            trades.save(trade);
            events.publish(ev);
            cancelled++;
        }
        log.info("auto-cancelled {} stale trades (TTL={}min, batchSize={})",
                cancelled, ttl.toMinutes(), batchSize);
        return cancelled;
    }
}
