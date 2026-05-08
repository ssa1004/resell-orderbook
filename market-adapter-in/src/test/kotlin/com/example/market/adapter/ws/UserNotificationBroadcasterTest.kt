package com.example.market.adapter.ws

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.messaging.simp.SimpMessagingTemplate

/**
 * KafkaListener 가 throw 한 예외는 Spring Kafka 의 DefaultErrorHandler 가 받아 3회 재시도 후
 * DLQ 로 보낸다. 이 클래스의 invariant: 파싱 실패와 STOMP 발신 실패는 절대 삼키지 않는다.
 */
class UserNotificationBroadcasterTest {

    private val messagingTemplate: SimpMessagingTemplate = mock()
    private val objectMapper = ObjectMapper()
    private val broadcaster = UserNotificationBroadcaster(messagingTemplate, objectMapper)

    @Test
    fun `valid event pushes to buyer and seller`() {
        val payload = """
            {
              "eventType": "TradeMatched",
              "tradeId": {"value": "trade-1"},
              "buyerId": {"value": "buyer-1"},
              "sellerId": {"value": "seller-1"},
              "occurredAt": "2026-05-04T10:00:00Z"
            }
        """.trimIndent()

        broadcaster.onTradeEvent(payload)

        verify(messagingTemplate).convertAndSendToUser(eq("buyer-1"), eq("/queue/notifications"), any())
        verify(messagingTemplate).convertAndSendToUser(eq("seller-1"), eq("/queue/notifications"), any())
    }

    @Test
    fun `malformed JSON propagates exception so DefaultErrorHandler can retry then DLQ`() {
        val poison = "{not-json"

        assertThatThrownBy { broadcaster.onTradeEvent(poison) }
            .isInstanceOf(JsonProcessingException::class.java)
        verify(messagingTemplate, never()).convertAndSendToUser(any<String>(), any(), any())
    }

    @Test
    fun `STOMP push failure propagates exception`() {
        val payload = """
            {"eventType":"TradeMatched","tradeId":{"value":"t-1"},
             "buyerId":{"value":"buyer-1"},"sellerId":{"value":"seller-1"}}
        """.trimIndent()
        whenever(messagingTemplate.convertAndSendToUser(eq("buyer-1"), any(), any<Any>()))
            .thenThrow(RuntimeException("broker offline"))

        assertThatThrownBy { broadcaster.onTradeEvent(payload) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("broker offline")
    }

    @Test
    fun `event without buyer or seller is dropped silently (no retry)`() {
        // 정산 이벤트 등 사용자 알림 대상이 아닌 메시지는 retry 해도 결과가 같으므로 그냥 종료
        val payload = """{"eventType":"SystemEvent","tradeId":{"value":"t-1"}}"""

        broadcaster.onTradeEvent(payload)

        verify(messagingTemplate, never()).convertAndSendToUser(any<String>(), any(), any())
    }

    @Test
    fun `single-side event (only buyer) sends one push`() {
        val payload = """
            {"eventType":"TradeCompleted","tradeId":{"value":"t-9"},
             "buyerId":{"value":"buyer-9"}}
        """.trimIndent()

        broadcaster.onTradeEvent(payload)

        verify(messagingTemplate, times(1)).convertAndSendToUser(eq("buyer-9"), any(), any<Any>())
    }
}
