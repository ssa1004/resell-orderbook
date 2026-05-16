package com.example.market.application.service

import com.example.market.application.command.RecordInspectionArrivalCommand
import com.example.market.application.exception.TradeNotFoundException
import com.example.market.application.port.`in`.RecordInspectionArrivalUseCase
import com.example.market.application.port.out.EventPublisher
import com.example.market.application.port.out.InspectionRequestRepository
import com.example.market.application.port.out.TradeRepository
import com.example.market.domain.inspection.InspectionRequest
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 검수센터 도착 처리 — Trade 를 INSPECTION_PENDING 으로 전이 + InspectionRequest.open.
 * 운영자(검수센터 직원)가 호출.
 */
@Service
open class RecordInspectionArrivalService(
    private val trades: TradeRepository,
    private val inspections: InspectionRequestRepository,
    private val events: EventPublisher,
    private val clock: Clock,
) : RecordInspectionArrivalUseCase {

    @Transactional
    override fun arrive(command: RecordInspectionArrivalCommand): InspectionRequest {
        val trade = trades.findById(command.tradeId)
            .orElseThrow { TradeNotFoundException(command.tradeId) }
        val now = clock.instant()
        val ev = trade.arriveAtInspection(now)
        trades.save(trade)

        val request = InspectionRequest.open(trade.id, now)
        inspections.save(request)

        events.publish(ev)
        log.info("inspection arrival recorded trade={} request={}", trade.id, request.id)
        return request
    }

    companion object {
        private val log = LoggerFactory.getLogger(RecordInspectionArrivalService::class.java)
    }
}
