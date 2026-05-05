package com.example.market.adapter.ws

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * 사용자별 거래 알림 broadcaster — STOMP user destination 활용.
 *
 * <p>흐름:
 * <ol>
 *   <li>매칭 / 결제 / 검수 / 정산 이벤트가 Kafka 에 발행</li>
 *   <li>이 컴포넌트가 수신, 영향받는 사용자 (buyerId / sellerId) 를 페이로드에서 추출</li>
 *   <li>{@code /user/<userId>/queue/notifications} 로 push</li>
 *   <li>해당 사용자의 STOMP session 에만 전송 (Spring 이 user → session 매핑 자동 관리)</li>
 * </ol>
 *
 * <p>구독자가 없으면 메시지는 그냥 drop — 별도 푸시 알림 (FCM / APNS) 은 별도 채널.</p>
 */
@Component
@ConditionalOnProperty(name = ["spring.kafka.bootstrap-servers"])
class UserNotificationBroadcaster(
    private val messagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [
            "market.tradematched",
            "market.paymentauthorized", "market.paymentrejected",
            "market.inspectionpassed", "market.inspectionfailed",
            "market.tradecompleted", "market.refundingstarted",
        ],
        groupId = "stomp-user-notifier",
    )
    fun onTradeEvent(payload: String) {
        runCatching {
            val node = objectMapper.readTree(payload)
            val notification = mapOf(
                "type" to (node.get("eventType")?.asText() ?: "TradeEvent"),
                "tradeId" to (node.get("tradeId")?.get("value")?.asText()),
                "occurredAt" to (node.get("occurredAt")?.asText()),
            )

            // buyerId / sellerId 가 있으면 양쪽에 push
            sequenceOf("buyerId", "sellerId")
                .mapNotNull { node.get(it)?.get("value")?.asText() }
                .forEach { userId ->
                    messagingTemplate.convertAndSendToUser(
                        userId, "/queue/notifications", notification
                    )
                }
        }.onFailure { log.warn("user notification skipped: {}", it.message) }
    }
}
