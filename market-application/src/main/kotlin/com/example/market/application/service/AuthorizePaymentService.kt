package com.example.market.application.service

import com.example.market.application.command.AuthorizePaymentCommand
import com.example.market.application.exception.TradeNotFoundException
import com.example.market.application.port.`in`.AuthorizePaymentUseCase
import com.example.market.application.port.out.EventPublisher
import com.example.market.application.port.out.PgClient
import com.example.market.application.port.out.TradeRepository
import com.example.market.domain.trading.Trade
import com.example.market.domain.trading.TradeStatus
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * TradeMatched 이벤트 컨슈머 → PG (결제 게이트웨이) authorize 호출 → 결과에 따라
 * Trade.authorizePayment 또는 cancelOnPaymentFailure 호출.
 *
 * 멱등 처리: Kafka 가 메시지를 최소 한 번 이상 전달 (at-least-once) 하므로 중복 호출이
 * 가능하다 → Trade 의 현재 상태가 CREATED 인 경우에만 진행하고 나머지는 그냥 건너뛴다.
 * PG 측 idempotency-key (PG 가 같은 결제 요청을 두 번 받아도 한 번만 결제하도록 식별하는
 * 키) 로는 TradeId 를 그대로 사용 — 같은 거래에 결제는 한 번만 발생.
 */
@Service
open class AuthorizePaymentService(
    private val trades: TradeRepository,
    private val pgClient: PgClient,
    private val events: EventPublisher,
    private val clock: Clock,
) : AuthorizePaymentUseCase {

    @Transactional
    override fun authorize(command: AuthorizePaymentCommand): Trade {
        val trade = trades.findById(command.tradeId)
            .orElseThrow { TradeNotFoundException(command.tradeId) }

        if (trade.status != TradeStatus.CREATED) {
            log.info("authorize idempotent skip — trade {} already in {}", trade.id, trade.status)
            return trade
        }

        val now = clock.instant()
        val req = PgClient.AuthorizeRequest(
            trade.id.toString(),                 // PG 측 멱등성 키 = tradeId (같은 거래에 결제는 한 번만)
            trade.feeSnapshot.buyerCharge,
            trade.id.toString(),
            trade.buyerId.value,
        )
        val result = pgClient.authorize(req)

        if (result.approved) {
            val ev = trade.authorizePayment(result.pgPaymentId!!, now)
            trades.save(trade)
            events.publish(ev)
            log.info("payment authorized trade={} pgPaymentId={}", trade.id, result.pgPaymentId)
        } else {
            val ev = trade.cancelOnPaymentFailure(
                "${result.errorCode}:${result.errorMessage}", now,
            )
            trades.save(trade)
            events.publish(ev)
            log.warn("payment rejected trade={} reason={}", trade.id, result.errorMessage)
        }
        return trade
    }

    companion object {
        private val log = LoggerFactory.getLogger(AuthorizePaymentService::class.java)
    }
}
