package com.example.market.adapter.ws

import com.example.market.adapter.kafka.MarketTopic
import com.example.market.adapter.web.dto.OrderBookView
import com.example.market.application.port.`in`.OrderBookQueryUseCase
import com.example.market.domain.catalog.SkuId
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * STOMP 기반 호가창 broadcaster.
 *
 * <p>Kafka 의 호가 변경 이벤트 (TradeMatched / *Placed / *Cancelled) 수신 → 영향받는 SKU
 * 의 orderbook snapshot 을 다시 조회 → STOMP destination
 * {@code /topic/orderbook/<skuId>} 로 publish. 해당 destination 을 SUBSCRIBE 한 모든
 * 클라이언트가 push 받음.</p>
 *
 * <p>구독자 추적 / cleanup 은 STOMP 가 자동 처리 — handler 코드 단순화.</p>
 *
 * <p>at-least-once OK — 같은 snapshot 이 두 번 가도 클라이언트 화면이 같은 상태로 두 번
 * 그려질 뿐. 멱등 안전.</p>
 */
@Component
@ConditionalOnProperty(name = ["spring.kafka.bootstrap-servers"])
class StompOrderBookBroadcaster(
    private val messagingTemplate: SimpMessagingTemplate,
    private val orderBookQuery: OrderBookQueryUseCase,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [
            MarketTopic.TRADE_MATCHED,
            MarketTopic.LISTING_PLACED, MarketTopic.BID_PLACED,
            MarketTopic.LISTING_CANCELLED, MarketTopic.BID_CANCELLED,
        ],
        groupId = "stomp-orderbook-broadcaster",
    )
    fun onOrderBookChanged(payload: String) {
        runCatching {
            val node = objectMapper.readTree(payload)
            val skuId = SkuId.of(node.get("skuId").get("value").asText())
            val snapshot = OrderBookView.from(orderBookQuery.view(skuId, 10))
            messagingTemplate.convertAndSend("/topic/orderbook/${skuId.value()}", snapshot)
        }.onFailure { log.warn("stomp orderbook broadcast skipped: {}", it.message) }
    }
}
