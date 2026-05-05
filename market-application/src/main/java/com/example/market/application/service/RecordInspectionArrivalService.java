package com.example.market.application.service;

import com.example.market.application.command.RecordInspectionArrivalCommand;
import com.example.market.application.exception.TradeNotFoundException;
import com.example.market.application.port.in.RecordInspectionArrivalUseCase;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.InspectionRequestRepository;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.inspection.InspectionRequest;
import com.example.market.domain.trading.Trade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 검수센터 도착 처리 — Trade 를 INSPECTION_PENDING 으로 전이 + InspectionRequest.open.
 * 운영자(검수센터 직원)가 호출.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecordInspectionArrivalService implements RecordInspectionArrivalUseCase {

    private final TradeRepository trades;
    private final InspectionRequestRepository inspections;
    private final EventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public InspectionRequest arrive(RecordInspectionArrivalCommand cmd) {
        Trade trade = trades.findById(cmd.tradeId())
                .orElseThrow(() -> new TradeNotFoundException(cmd.tradeId()));
        Instant now = clock.instant();
        var ev = trade.arriveAtInspection(now);
        trades.save(trade);

        InspectionRequest request = InspectionRequest.open(trade.id(), now);
        inspections.save(request);

        events.publish(ev);
        log.info("inspection arrival recorded trade={} request={}", trade.id(), request.id());
        return request;
    }
}
