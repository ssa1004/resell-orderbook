package com.example.market.application.service;

import com.example.market.application.command.RecordSellerShippingCommand;
import com.example.market.application.exception.TradeNotFoundException;
import com.example.market.application.exception.UnauthorizedTradeOperationException;
import com.example.market.application.port.in.RecordSellerShippingUseCase;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.trading.Trade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecordSellerShippingService implements RecordSellerShippingUseCase {

    private final TradeRepository trades;
    private final EventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public void recordShipping(RecordSellerShippingCommand cmd) {
        Trade trade = trades.findById(cmd.tradeId())
                .orElseThrow(() -> new TradeNotFoundException(cmd.tradeId()));
        if (!trade.sellerId().equals(cmd.requestor())) {
            throw new UnauthorizedTradeOperationException(trade.id(), cmd.requestor(), "shipping");
        }
        Instant now = clock.instant();
        var ev = trade.startSellerShipping(now);
        trades.save(trade);
        events.publish(ev);
        log.info("seller shipping recorded trade={} tracking={}",
                trade.id(), SensitiveLogging.mask(cmd.trackingNumber()));
    }
}
