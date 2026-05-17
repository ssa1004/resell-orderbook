package com.example.market.adapter.out.cache

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import java.nio.charset.StandardCharsets
import java.util.function.Consumer

/**
 * Redis pub/sub 구독자 — invalidate 메시지를 받아 등록된 핸들러 ([TwoTierMarketStatsCache]
 * 의 L1 evict 콜백) 를 호출.
 *
 * 자기 자신이 publish 한 메시지는 무시 — sourceId 비교로 round-trip 차단.
 *
 * ### 예외 처리
 *
 * Listener 가 throw 하면 Redisson / Lettuce 의 동작이 구현체별로 다르고 (메시지 1건 손실 또는
 * subscriber 재시작) 다른 메시지 처리에 영향을 줄 수 있어, 핸들러 내부 예외는 모두 swallow + log.
 * invalidate 1건 실패는 정합성에 치명적이지 않다 (TTL 안전망).
 */
class CacheInvalidationSubscriber(
    private val objectMapper: ObjectMapper,
    private val selfSourceId: String,
    private val onInvalidate: Consumer<String>,
) : MessageListener {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun onMessage(message: Message, pattern: ByteArray?) {
        try {
            val json = String(message.body, StandardCharsets.UTF_8)
            val msg = objectMapper.readValue(json, CacheInvalidationMessage::class.java)
            if (selfSourceId == msg.sourceId) {
                // 자기 자신이 보낸 메시지 — 이미 반영했음. 무시.
                return
            }
            onInvalidate.accept(msg.key)
            log.debug("cache invalidate received key={} from={}", msg.key, msg.sourceId)
        } catch (e: Exception) {
            // 잘못된 메시지 / 핸들러 예외 — 다른 메시지 처리에 영향 없도록 모두 swallow.
            log.warn("cache invalidate handler 실패 (무시) reason={}", e.message)
        }
    }
}
