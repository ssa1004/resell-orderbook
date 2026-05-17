package com.example.market.adapter.out.cache

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock

/**
 * cache evict 메시지를 Redis pub/sub 채널로 broadcast.
 *
 * [TwoTierMarketStatsCache] 가 fresh 값을 L1/L2 에 채운 직후 호출 — 같은 sku 의 L1 을
 * 가지고 있는 모든 pod 가 즉시 invalidate 하도록 알린다. 이 신호를 받지 못한 pod 는 다음 조회 시
 * L1 hard-TTL (1초) 까지 stale 값을 본다.
 *
 * ### at-most-once
 *
 * Redis pub/sub 은 *fire-and-forget* — 구독자가 일시적으로 끊겨 있으면 메시지 유실. 이 한계를
 * 그대로 받아들이고 [TwoTierMarketStatsCache] 의 짧은 L1 TTL 을 안전망으로 둔다 (메시지가
 * 유실돼도 1초 안에는 stale 이 사라짐). pub/sub 은 평균 stale 시간을 *몇 ms* 로 압축하기 위한
 * 장치이지 정합성 도구가 아니다.
 */
class CacheInvalidationPublisher(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val channel: String,
    private val sourceId: String,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 무효화 메시지를 채널로 publish. 실패해도 throw 하지 않음 (TTL 에 의한 안전망 존재 →
     * 호출자에게 영향 안 줌).
     */
    fun publish(key: String) {
        try {
            val msg = CacheInvalidationMessage(key, sourceId, clock.millis())
            val json = objectMapper.writeValueAsString(msg)
            val received = redis.convertAndSend(channel, json)
            log.debug("cache invalidate published key={} subscribers={}", key, received)
        } catch (e: JsonProcessingException) {
            log.warn("cache invalidate 직렬화 실패 (무시) key={} reason={}", key, e.message)
        } catch (e: Exception) {
            // Redis 장애 — 메시지 유실. L1 TTL 이 안전망 — 호출자에게 throw 안 함.
            log.warn("cache invalidate publish 실패 (무시) key={} reason={}", key, e.message)
        }
    }

    fun sourceId(): String = sourceId

    fun channel(): String = channel
}
