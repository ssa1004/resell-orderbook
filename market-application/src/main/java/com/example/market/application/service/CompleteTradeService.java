package com.example.market.application.service;

import com.example.market.application.command.CompleteTradeCommand;
import com.example.market.application.exception.TradeNotFoundException;
import com.example.market.application.exception.UnauthorizedTradeOperationException;
import com.example.market.application.port.in.CompleteTradeUseCase;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.trading.Trade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompleteTradeService implements CompleteTradeUseCase {

    private final TradeRepository trades;
    private final EventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public Trade complete(CompleteTradeCommand cmd) {
        Trade trade = trades.findById(cmd.tradeId())
                .orElseThrow(() -> new TradeNotFoundException(cmd.tradeId()));
        if (!trade.buyerId().equals(cmd.requestor())) {
            throw new UnauthorizedTradeOperationException(trade.id(), cmd.requestor(), "complete");
        }
        var ev = trade.complete(clock.instant());
        trades.save(trade);
        events.publish(ev);
        log.info("trade completed id={} sellerNet={}", trade.id(), ev.sellerNet());
        return trade;
    }
}
