package com.example.market.adapter.out.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CacheInvalidationSubscriber} 단위 — pub/sub 채널 자체는 다른 IT 에서 검증, 여기는 메시지
 * 라우팅 로직만.
 *
 * <ul>
 *   <li>다른 pod 메시지 → onInvalidate 호출</li>
 *   <li>자기 pod 메시지 → 무시 (round-trip 방지)</li>
 *   <li>잘못된 메시지 → 예외 swallow + 다른 메시지 처리에 영향 없음</li>
 * </ul>
 */
class CacheInvalidationSubscriberTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void otherSourceMessage_invokesHandler() throws Exception {
        List<String> evicted = new ArrayList<>();
        var subscriber = new CacheInvalidationSubscriber(json, "self-pod", evicted::add);

        var msg = json.writeValueAsString(new CacheInvalidationMessage(
                "sku-123", "other-pod", System.currentTimeMillis()));
        subscriber.onMessage(new DefaultMessage("ch".getBytes(), msg.getBytes(StandardCharsets.UTF_8)), null);

        assertThat(evicted).containsExactly("sku-123");
    }

    @Test
    void selfMessage_isIgnored() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var subscriber = new CacheInvalidationSubscriber(json, "self-pod", k -> calls.incrementAndGet());

        var msg = json.writeValueAsString(new CacheInvalidationMessage(
                "sku-123", "self-pod", System.currentTimeMillis()));
        subscriber.onMessage(new DefaultMessage("ch".getBytes(), msg.getBytes(StandardCharsets.UTF_8)), null);

        assertThat(calls.get()).isZero();
    }

    @Test
    void malformedMessage_doesNotThrow() {
        AtomicInteger calls = new AtomicInteger();
        var subscriber = new CacheInvalidationSubscriber(json, "self-pod", k -> calls.incrementAndGet());

        // 잘못된 JSON.
        subscriber.onMessage(new DefaultMessage("ch".getBytes(),
                "not-json".getBytes(StandardCharsets.UTF_8)), null);

        // 두 번째 메시지는 정상 — 첫 번째 실패가 다음 처리에 영향 없는지 확인.
        try {
            var ok = json.writeValueAsString(new CacheInvalidationMessage(
                    "sku-99", "other-pod", System.currentTimeMillis()));
            subscriber.onMessage(new DefaultMessage("ch".getBytes(),
                    ok.getBytes(StandardCharsets.UTF_8)), null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void handlerThrowsIsSwallowed() throws Exception {
        var subscriber = new CacheInvalidationSubscriber(json, "self-pod", k -> {
            throw new RuntimeException("boom");
        });

        var msg = json.writeValueAsString(new CacheInvalidationMessage(
                "sku-1", "other", System.currentTimeMillis()));
        // throw 하지 않아야 함
        subscriber.onMessage(new DefaultMessage("ch".getBytes(),
                msg.getBytes(StandardCharsets.UTF_8)), null);
    }
}
