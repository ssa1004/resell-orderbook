package com.example.market.application.service;

import com.example.market.application.command.StartBuyerShippingCommand;
import com.example.market.application.exception.TradeNotFoundException;
import com.example.market.application.port.in.StartBuyerShippingUseCase;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.trading.Trade;
import com.example.market.domain.trading.TradeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * InspectionPassed 컨슈머. trackingNumber 는 검수센터의 발송 시스템에서 받음.
 *
 * <p>idempotent: trade 가 이미 BUYER_SHIPPING 또는 그 이후면 skip.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StartBuyerShippingService implements StartBuyerShippingUseCase {

    private final TradeRepository trades;
    private final EventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public void start(StartBuyerShippingCommand cmd) {
        Trade trade = trades.findById(cmd.tradeId())
                .orElseThrow(() -> new TradeNotFoundException(cmd.tradeId()));
        if (trade.status() != TradeStatus.INSPECTION_PASSED) {
            log.info("startBuyerShipping idempotent skip — trade {} in {}", trade.id(), trade.status());
            return;
        }
        var ev = trade.startBuyerShipping(clock.instant());
        trades.save(trade);
        events.publish(ev);
        log.info("buyer shipping started trade={} tracking={}",
                trade.id(), SensitiveLogging.mask(cmd.trackingNumber()));
    }
}
