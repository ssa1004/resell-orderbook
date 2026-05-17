package com.example.market.adapter.out.cache

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * cross-pod L1 evict 메시지. Redis pub/sub 채널로 모든 인스턴스에 broadcast 된다.
 *
 * @param key      무효화할 cache key (SKU id 의 string)
 * @param sourceId 메시지를 발행한 pod 의 식별자 — 자기 자신이 보낸 메시지는 처리 안 함
 *                 (이미 자기 캐시는 갱신했고, 한 pod 안에서 publish → subscribe 자기 응답으로 또
 *                 evict 하면 의미 없는 round-trip).
 * @param at       publish 시각 (epoch millis). 디버깅 / metric 용. invalidate 결정에는 안 쓴다.
 */
@JvmRecord
data class CacheInvalidationMessage @JsonCreator constructor(
    @JsonProperty("key") val key: String,
    @JsonProperty("source") val sourceId: String,
    @JsonProperty("at") val at: Long,
) {
    init {
        require(key.isNotBlank()) { "key must not be blank" }
    }
}
