package com.example.market.adapter.ws

import com.example.market.domain.catalog.SkuId
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * 호가창 변경 이벤트 (TradeMatched / ListingPlaced / BidPlaced / *Cancelled) → WebSocket broadcast.
 * Kafka 컨슈머 — at-least-once OK (snapshot 다시 보냄).
 */
@Component
@ConditionalOnProperty(name = ["spring.kafka.bootstrap-servers"])
class OrderBookEventBroadcaster(
    private val handler: OrderBookWebSocketHandler,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [
        "market.tradematched",
        "market.listingplaced", "market.bidplaced",
        "market.listingcancelled", "market.bidcancelled",
    ], groupId = "orderbook-broadcaster")
    fun onOrderBookChanged(payload: String) {
        runCatching {
            val node = objectMapper.readTree(payload)
            val skuId = SkuId.of(node.get("skuId").get("value").asText())
            handler.broadcastChange(skuId)
        }.onFailure { log.warn("orderbook broadcast skipped: {}", it.message) }
    }
}
