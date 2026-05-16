package com.example.market.application.service

import com.example.market.application.command.RecordInspectionResultCommand
import com.example.market.application.exception.InspectionRequestNotFoundException
import com.example.market.application.exception.TradeNotFoundException
import com.example.market.application.port.`in`.RecordInspectionResultUseCase
import com.example.market.application.port.out.EventPublisher
import com.example.market.application.port.out.InspectionRequestRepository
import com.example.market.application.port.out.TradeRepository
import com.example.market.domain.inspection.InspectionOutcome
import com.example.market.domain.inspection.InspectionRequest
import com.example.market.domain.inspection.InspectionResult
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 검수 결과 기록 — InspectionRequest 결정 + Trade 의 PASS/FAIL 분기.
 *
 * 같은 트랜잭션에서:
 * - InspectionRequest.decide → DECIDED
 * - Trade.passInspection (PASS) 또는 Trade.failInspection (FAIL)
 * - 도메인 이벤트 2개 publish (InspectionDecided + Trade 의 InspectionPassed/Failed)
 *
 * 다음 단계 (BuyerShipping / Refunding) 는 별도 컨슈머가 처리.
 */
@Service
open class RecordInspectionResultService(
    private val inspections: InspectionRequestRepository,
    private val trades: TradeRepository,
    private val events: EventPublisher,
    private val clock: Clock,
) : RecordInspectionResultUseCase {

    @Transactional
    override fun record(command: RecordInspectionResultCommand): InspectionRequest {
        val request = inspections.findById(command.requestId)
            .orElseThrow { InspectionRequestNotFoundException(command.requestId) }

        // 사진 첨부
        command.photoUrls.forEach(request::addPhoto)

        val result = if (command.outcome == InspectionOutcome.PASS) {
            InspectionResult.pass(command.note)
        } else {
            // FAIL 시 도메인이 reason non-null + non-blank 를 require — 여기선 그대로 위임.
            InspectionResult.fail(command.reason!!, command.note)
        }

        val now = clock.instant()
        val inspectionEvent = request.decide(result, now)
        inspections.save(request)

        val trade = trades.findById(request.tradeId)
            .orElseThrow { TradeNotFoundException(request.tradeId) }

        if (command.outcome == InspectionOutcome.PASS) {
            val ev = trade.passInspection(now)
            trades.save(trade)
            events.publishAll(inspectionEvent, ev)
            log.info("inspection PASS request={} trade={}", request.id, trade.id)
        } else {
            val ev = trade.failInspection(command.reason!!, now)
            trades.save(trade)
            events.publishAll(inspectionEvent, ev)
            log.warn("inspection FAIL request={} trade={} reason={}", request.id, trade.id, command.reason)
        }
        return request
    }

    companion object {
        private val log = LoggerFactory.getLogger(RecordInspectionResultService::class.java)
    }
}
