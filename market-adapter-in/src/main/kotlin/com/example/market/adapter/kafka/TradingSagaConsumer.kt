package com.example.market.adapter.kafka

import com.example.market.application.command.AuthorizePaymentCommand
import com.example.market.application.command.StartBuyerShippingCommand
import com.example.market.application.port.`in`.AuthorizePaymentUseCase
import com.example.market.application.port.`in`.RefundBuyerUseCase
import com.example.market.application.port.`in`.SettleTradeUseCase
import com.example.market.application.port.`in`.StartBuyerShippingUseCase
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * 코레오그래피 Saga (중앙 조정자 없이 각 단계가 이벤트를 보고 자기 책임을 수행하는 분산
 * 트랜잭션 패턴) 의 컨슈머 — Outbox 가 발행한 도메인 이벤트를 받아 다음 단계를 트리거.
 *
 * <p>각 메서드는 Trade 의 다음 단계 use case 를 호출한다. Kafka 가 메시지를 최소 한 번 이상
 * 전달하기 (at-least-once) 때문에 같은 메시지가 두 번 들어올 수 있고, use case 가 그 안에서
 * Trade 의 현재 상태를 체크해 이미 처리된 거래는 건너뛴다 (멱등 처리).</p>
 *
 * <p>처리 실패 시 Spring Kafka 의 DefaultErrorHandler 가 3회 재시도한 뒤, 처리 못한 메시지를
 * DLQ (Dead Letter Queue, 실패 메시지를 격리해두는 별도 토픽) 로 보낸다.</p>
 */
@Component
@ConditionalOnProperty(name = ["spring.kafka.bootstrap-servers"])
class TradingSagaConsumer(
    private val authorizePayment: AuthorizePaymentUseCase,
    private val startBuyerShipping: StartBuyerShippingUseCase,
    private val refundBuyer: RefundBuyerUseCase,
    private val settleTrade: SettleTradeUseCase,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [MarketTopic.TRADE_MATCHED], groupId = "saga-authorize")
    fun onTradeMatched(payload: String) {
        val tradeId = objectMapper.parseEvent(payload).requireTradeId()
        log.info("[saga] TradeMatched → authorizePayment trade={}", tradeId)
        authorizePayment.authorize(AuthorizePaymentCommand(tradeId))
    }

    @KafkaListener(topics = [MarketTopic.INSPECTION_PASSED], groupId = "saga-buyer-shipping")
    fun onInspectionPassed(payload: String) {
        val tradeId = objectMapper.parseEvent(payload).requireTradeId()
        log.info("[saga] InspectionPassed → startBuyerShipping trade={}", tradeId)
        startBuyerShipping.start(StartBuyerShippingCommand(tradeId, "AUTO-$tradeId"))
    }

    @KafkaListener(topics = [MarketTopic.INSPECTION_FAILED], groupId = "saga-refund")
    fun onInspectionFailed(payload: String) {
        val tradeId = objectMapper.parseEvent(payload).requireTradeId()
        log.info("[saga] InspectionFailed → refundBuyer trade={}", tradeId)
        refundBuyer.refund(tradeId)
    }

    @KafkaListener(topics = [MarketTopic.TRADE_COMPLETED], groupId = "saga-settle")
    fun onTradeCompleted(payload: String) {
        val tradeId = objectMapper.parseEvent(payload).requireTradeId()
        log.info("[saga] TradeCompleted → settle trade={}", tradeId)
        settleTrade.settle(tradeId)
    }
}
