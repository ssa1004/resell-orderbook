package com.example.market.adapter.ws

import com.example.market.adapter.kafka.MarketTopic
import com.example.market.adapter.kafka.parseEvent
import com.example.market.adapter.kafka.requireSkuId
import com.example.market.adapter.web.dto.OrderBookView
import com.example.market.application.port.`in`.OrderBookQueryUseCase
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * STOMP (WebSocket 위에서 동작하는 메시지 지향 프로토콜. 구독/발행 모델 제공) 기반의 호가창
 * broadcaster.
 *
 * <p>Kafka 의 호가 변경 이벤트 (TradeMatched / *Placed / *Cancelled) 수신 → 영향받는 SKU 의
 * 호가창 스냅샷을 다시 조회 → STOMP 목적지 {@code /topic/orderbook/<skuId>} 로 publish. 해당
 * 목적지를 SUBSCRIBE 해둔 모든 클라이언트에게 자동으로 push 된다.</p>
 *
 * <p>구독자 목록 관리와 끊긴 세션 정리는 STOMP 브로커가 자동 처리 — 핸들러 코드가 단순해진다.</p>
 *
 * <p>Kafka 가 메시지를 최소 한 번 이상 전달 (at-least-once) 해도 안전 — 같은 스냅샷이 두 번
 * 가도 클라이언트 화면이 같은 상태로 두 번 그려질 뿐, 결과가 달라지지 않는다 (멱등).</p>
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
            val skuId = objectMapper.parseEvent(payload).requireSkuId()
            val snapshot = OrderBookView.from(orderBookQuery.view(skuId, 10))
            messagingTemplate.convertAndSend("/topic/orderbook/${skuId.value}", snapshot)
        }.onFailure { log.warn("stomp orderbook broadcast skipped: {}", it.message) }
    }
}
