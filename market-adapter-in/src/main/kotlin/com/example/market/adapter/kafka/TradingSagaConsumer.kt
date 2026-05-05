package com.example.market.adapter.kafka

import com.example.market.application.command.AuthorizePaymentCommand
import com.example.market.application.command.StartBuyerShippingCommand
import com.example.market.application.port.`in`.AuthorizePaymentUseCase
import com.example.market.application.port.`in`.RefundBuyerUseCase
import com.example.market.application.port.`in`.SettleTradeUseCase
import com.example.market.application.port.`in`.StartBuyerShippingUseCase
import com.example.market.domain.trading.TradeId
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * Choreography Saga 컨슈머 — Outbox 가 publish 한 도메인 이벤트를 수신.
 *
 * <p>각 메서드는 Trade 의 다음 단계 use case 를 트리거. at-least-once 보장이라
 * use case 자체가 idempotent (Trade 상태 체크).</p>
 *
 * <p>실패 시 DefaultErrorHandler 가 3회 재시도 후 DLQ topic publish.</p>
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

    @KafkaListener(topics = ["market.trademetched", "market.trade-matched", "market.trade.matched"],
                   groupId = "saga-authorize")
    fun onTradeMatched(payload: String) {
        val node = objectMapper.readTree(payload)
        val tradeId = TradeId.of(node.get("tradeId").get("value").asText())
        log.info("[saga] TradeMatched → authorizePayment trade={}", tradeId)
        authorizePayment.authorize(AuthorizePaymentCommand(tradeId))
    }

    @KafkaListener(topics = ["market.inspectionpassed"], groupId = "saga-buyer-shipping")
    fun onInspectionPassed(payload: String) {
        val node = objectMapper.readTree(payload)
        val tradeId = TradeId.of(node.get("tradeId").get("value").asText())
        log.info("[saga] InspectionPassed → startBuyerShipping trade={}", tradeId)
        startBuyerShipping.start(StartBuyerShippingCommand(tradeId, "AUTO-" + tradeId))
    }

    @KafkaListener(topics = ["market.inspectionfailed"], groupId = "saga-refund")
    fun onInspectionFailed(payload: String) {
        val node = objectMapper.readTree(payload)
        val tradeId = TradeId.of(node.get("tradeId").get("value").asText())
        log.info("[saga] InspectionFailed → refundBuyer trade={}", tradeId)
        refundBuyer.refund(tradeId)
    }

    @KafkaListener(topics = ["market.tradecompleted"], groupId = "saga-settle")
    fun onTradeCompleted(payload: String) {
        val node = objectMapper.readTree(payload)
        val tradeId = TradeId.of(node.get("tradeId").get("value").asText())
        log.info("[saga] TradeCompleted → settle trade={}", tradeId)
        settleTrade.settle(tradeId)
    }
}
