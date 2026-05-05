package com.example.market.adapter.ws

import com.example.market.adapter.web.dto.OrderBookView
import com.example.market.application.port.`in`.OrderBookQueryUseCase
import com.example.market.domain.catalog.SkuId
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocket 호가창 — 클라이언트가 SKU 별로 subscribe, OrderBookSnapshot 을 push.
 *
 * <p>Path: {@code /ws/orderbook?skuId=<uuid>} — query param 으로 SKU 식별.
 * 시세 변경 (TradeMatched/ListingPlaced/ListingCancelled 등) 이벤트를 받으면
 * 해당 SKU 구독자에게 broadcast.</p>
 *
 * <p>broadcast 트리거는 별도 컴포넌트 (OrderBookEventBroadcaster) 가 Kafka 컨슈머로 동작 — 분리된 책임.</p>
 */
class OrderBookWebSocketHandler(
    private val orderBookQuery: OrderBookQueryUseCase,
    private val objectMapper: ObjectMapper,
) : TextWebSocketHandler() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val sessions: MutableMap<SkuId, MutableSet<WebSocketSession>> = ConcurrentHashMap()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val skuId = extractSkuId(session) ?: run {
            session.close(CloseStatus.BAD_DATA.withReason("missing skuId"))
            return
        }
        sessions.computeIfAbsent(skuId) { ConcurrentHashMap.newKeySet() }.add(session)
        // 초기 snapshot 전송
        val snapshot = OrderBookView.from(orderBookQuery.view(skuId, 10))
        session.sendMessage(TextMessage(objectMapper.writeValueAsString(snapshot)))
        log.info("ws connected sku={} session={}", skuId, session.id)
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        sessions.values.forEach { it.remove(session) }
        log.info("ws closed session={} status={}", session.id, status)
    }

    /** 외부 (broadcaster) 가 SKU 별 변경 시 호출. */
    fun broadcastChange(skuId: SkuId) {
        val subs = sessions[skuId] ?: return
        if (subs.isEmpty()) return
        val snapshot = OrderBookView.from(orderBookQuery.view(skuId, 10))
        val msg = TextMessage(objectMapper.writeValueAsString(snapshot))
        subs.removeIf { !it.isOpen }
        subs.forEach { sess ->
            runCatching { sess.sendMessage(msg) }
                .onFailure { ex -> log.warn("ws send failed session={} reason={}", sess.id, ex.message) }
        }
    }

    private fun extractSkuId(session: WebSocketSession): SkuId? {
        val query = session.uri?.query ?: return null
        val skuParam = query.split("&").firstOrNull { it.startsWith("skuId=") }
            ?.substringAfter("=") ?: return null
        return runCatching { SkuId.of(skuParam) }.getOrNull()
    }
}
