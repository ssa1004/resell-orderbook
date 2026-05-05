package com.example.market.application.service;

import com.example.market.application.command.RecordInspectionResultCommand;
import com.example.market.application.exception.InspectionRequestNotFoundException;
import com.example.market.application.exception.TradeNotFoundException;
import com.example.market.application.port.in.RecordInspectionResultUseCase;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.InspectionRequestRepository;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.inspection.InspectionOutcome;
import com.example.market.domain.inspection.InspectionRequest;
import com.example.market.domain.inspection.InspectionResult;
import com.example.market.domain.trading.Trade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 검수 결과 기록 — InspectionRequest 결정 + Trade 의 PASS/FAIL 분기.
 *
 * <p>같은 트랜잭션에서:</p>
 * <ul>
 *   <li>InspectionRequest.decide → DECIDED</li>
 *   <li>Trade.passInspection (PASS) 또는 Trade.failInspection (FAIL)</li>
 *   <li>도메인 이벤트 2개 publish (InspectionDecided + Trade 의 InspectionPassed/Failed)</li>
 * </ul>
 *
 * <p>다음 단계 (BuyerShipping / Refunding) 는 별도 컨슈머가 처리.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecordInspectionResultService implements RecordInspectionResultUseCase {

    private final InspectionRequestRepository inspections;
    private final TradeRepository trades;
    private final EventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public InspectionRequest record(RecordInspectionResultCommand cmd) {
        InspectionRequest request = inspections.findById(cmd.requestId())
                .orElseThrow(() -> new InspectionRequestNotFoundException(cmd.requestId()));

        // 사진 첨부
        if (cmd.photoUrls() != null) {
            cmd.photoUrls().forEach(request::addPhoto);
        }

        InspectionResult result = cmd.outcome() == InspectionOutcome.PASS
                ? InspectionResult.pass(cmd.note())
                : InspectionResult.fail(cmd.reason(), cmd.note());

        Instant now = clock.instant();
        var inspectionEvent = request.decide(result, now);
        inspections.save(request);

        Trade trade = trades.findById(request.tradeId())
                .orElseThrow(() -> new TradeNotFoundException(request.tradeId()));

        if (cmd.outcome() == InspectionOutcome.PASS) {
            var ev = trade.passInspection(now);
            trades.save(trade);
            events.publishAll(inspectionEvent, ev);
            log.info("inspection PASS request={} trade={}", request.id(), trade.id());
        } else {
            var ev = trade.failInspection(cmd.reason(), now);
            trades.save(trade);
            events.publishAll(inspectionEvent, ev);
            log.warn("inspection FAIL request={} trade={} reason={}", request.id(), trade.id(), cmd.reason());
        }
        return request;
    }
}
