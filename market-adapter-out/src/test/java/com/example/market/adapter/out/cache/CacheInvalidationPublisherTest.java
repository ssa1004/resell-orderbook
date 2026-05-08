package com.example.market.adapter.out.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CacheInvalidationPublisherTest {

    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-08T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void publishesJsonWithSourceId() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        var publisher = new CacheInvalidationPublisher(redis, json, "ch", "pod-1", clock);

        publisher.publish("sku-42");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq("ch"), body.capture());
        assertThat(body.getValue())
                .contains("\"key\":\"sku-42\"")
                .contains("\"source\":\"pod-1\"");
    }

    @Test
    void redisFailureIsSwallowed() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doThrow(new RuntimeException("redis down")).when(redis).convertAndSend(eq("ch"), org.mockito.ArgumentMatchers.anyString());

        var publisher = new CacheInvalidationPublisher(redis, json, "ch", "pod-1", clock);
        // 호출자에게 throw 안 함 (TTL 안전망 신뢰).
        publisher.publish("sku-42");
    }
}
