package com.example.market.adapter.ws

import com.example.market.adapter.kafka.MarketTopic
import com.fasterxml.jackson.databind.JsonNode
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
 *
 * <p><b>실패 처리</b>: 페이로드 파싱 실패와 STOMP push 실패는 둘 다 컨테이너로 throw 한다.
 * Spring Kafka 의 {@code DefaultErrorHandler} 가 3회 재시도 후 {@code <원본>-dlt} DLQ
 * 로 보낸다 ({@link com.example.market.adapter.out.messaging.DlqHandlerConfig}). 한 줄
 * warn 만 남기고 끝내면 알림이 영영 사라지므로 명시적 재시도 + DLQ 경로를 거치게 한다.</p>
 *
 * <p><b>예외 — 의미 없는 페이로드</b>: 알림 대상 userId 가 하나도 없으면 (정산/시스템 이벤트
 * 등) 재시도해도 결과가 같으므로 retry 하지 않고 debug 로그만 남기고 종료.</p>
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
            MarketTopic.TRADE_MATCHED,
            MarketTopic.PAYMENT_AUTHORIZED, MarketTopic.PAYMENT_REJECTED,
            MarketTopic.INSPECTION_PASSED, MarketTopic.INSPECTION_FAILED,
            MarketTopic.TRADE_COMPLETED, MarketTopic.REFUNDING_STARTED,
        ],
        groupId = "stomp-user-notifier",
    )
    fun onTradeEvent(payload: String) {
        // 파싱 실패 → JsonProcessingException → 컨테이너로 propagate → 3회 재시도 후 DLQ
        val node: JsonNode = objectMapper.readTree(payload)

        val notification = mapOf(
            "type" to (node.get("eventType")?.asText() ?: "TradeEvent"),
            "tradeId" to (node.get("tradeId")?.get("value")?.asText()),
            "occurredAt" to (node.get("occurredAt")?.asText()),
        )

        val recipients = sequenceOf("buyerId", "sellerId")
            .mapNotNull { node.get(it)?.get("value")?.asText() }
            .toList()
        if (recipients.isEmpty()) {
            // buyer/seller 가 없는 이벤트는 사용자 알림 대상이 아니다 — 재시도 의미 없음
            log.debug("user notification skipped (no recipient): type={}", notification["type"])
            return
        }

        // 한 명이라도 push 가 실패하면 throw — 부분 성공 후 재시도되면 중복 알림이 되겠지만
        // (at-least-once) 알림이 영영 사라지는 것보다는 낫다.
        recipients.forEach { userId ->
            messagingTemplate.convertAndSendToUser(userId, "/queue/notifications", notification)
        }
    }
}
