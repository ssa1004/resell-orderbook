package com.example.market.adapter.out.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;

/**
 * cache evict 메시지를 Redis pub/sub 채널로 broadcast.
 *
 * <p>{@link TwoTierMarketStatsCache} 가 fresh 값을 L1/L2 에 채운 직후 호출 — 같은 sku 의 L1 을
 * 가지고 있는 모든 pod 가 즉시 invalidate 하도록 알린다. 이 신호를 받지 못한 pod 는 다음 조회 시
 * L1 hard-TTL (1초) 까지 stale 값을 본다.</p>
 *
 * <h3>at-most-once</h3>
 *
 * <p>Redis pub/sub 은 *fire-and-forget* — 구독자가 일시적으로 끊겨 있으면 메시지 유실. 이 한계를
 * 그대로 받아들이고 {@link TwoTierMarketStatsCache} 의 짧은 L1 TTL 을 안전망으로 둔다 (메시지가
 * 유실돼도 1초 안에는 stale 이 사라짐). pub/sub 은 평균 stale 시간을 *몇 ms* 로 압축하기 위한
 * 장치이지 정합성 도구가 아니다.</p>
 */
@Slf4j
public class CacheInvalidationPublisher {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String channel;
    private final String sourceId;
    private final Clock clock;

    public CacheInvalidationPublisher(StringRedisTemplate redis,
                                      ObjectMapper objectMapper,
                                      String channel,
                                      String sourceId,
                                      Clock clock) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.channel = channel;
        this.sourceId = sourceId;
        this.clock = clock;
    }

    /**
     * 무효화 메시지를 채널로 publish. 실패해도 throw 하지 않음 (TTL 에 의한 안전망 존재 →
     * 호출자에게 영향 안 줌).
     */
    public void publish(String key) {
        try {
            CacheInvalidationMessage msg = new CacheInvalidationMessage(
                    key, sourceId, clock.millis());
            String json = objectMapper.writeValueAsString(msg);
            Long received = redis.convertAndSend(channel, json);
            log.debug("cache invalidate published key={} subscribers={}", key, received);
        } catch (JsonProcessingException e) {
            log.warn("cache invalidate 직렬화 실패 (무시) key={} reason={}", key, e.getMessage());
        } catch (Exception e) {
            // Redis 장애 — 메시지 유실. L1 TTL 이 안전망 — 호출자에게 throw 안 함.
            log.warn("cache invalidate publish 실패 (무시) key={} reason={}", key, e.getMessage());
        }
    }

    public String sourceId() { return sourceId; }
    public String channel() { return channel; }
}
