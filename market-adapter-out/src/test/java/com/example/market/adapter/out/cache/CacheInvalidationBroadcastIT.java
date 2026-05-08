package com.example.market.adapter.out.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.redis.testcontainers.RedisContainer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Redis (Testcontainer) 위에서 publish → subscribe → handler 호출이 한 round-trip 안에 도는지
 * 검증. Docker 없으면 자동 skip.
 */
@Testcontainers(disabledWithoutDocker = true)
class CacheInvalidationBroadcastIT {

    private static final RedisContainer REDIS = new RedisContainer(
            DockerImageName.parse("redis:7-alpine"));

    private static LettuceConnectionFactory cf;
    private static StringRedisTemplate redis;
    private static final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String CHANNEL = "test:cache:invalidate";

    @BeforeAll
    static void start() {
        REDIS.start();
        cf = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getRedisPort());
        cf.afterPropertiesSet();
        redis = new StringRedisTemplate(cf);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stop() {
        if (cf != null) cf.destroy();
        if (REDIS.isRunning()) REDIS.stop();
    }

    @Test
    void publishedMessageReachesOtherSubscriberOnly() {
        // pod A 가 publish, pod A/B 가 모두 subscribe.
        var publisher = new CacheInvalidationPublisher(
                redis, objectMapper, CHANNEL, "pod-A", Clock.systemUTC());

        List<String> evictedAtA = new ArrayList<>();
        List<String> evictedAtB = new ArrayList<>();

        var listenerA = newListenerContainer(new CacheInvalidationSubscriber(
                objectMapper, "pod-A", evictedAtA::add));
        var listenerB = newListenerContainer(new CacheInvalidationSubscriber(
                objectMapper, "pod-B", evictedAtB::add));

        try {
            // listener container 가 Redis 와 핸드셰이크할 시간을 주기 위해 살짝 대기.
            Awaitility.await().atMost(Duration.ofSeconds(3))
                    .until(() -> listenerA.isRunning() && listenerB.isRunning());
            // pub/sub 구독이 실제 broker 에 등록되기까지 잠시 대기 — Lettuce 가 실제 SUBSCRIBE 명령을
            // 보내는 시간 (수십 ms) 을 잡기 위함.
            Thread.sleep(300);

            publisher.publish("sku-A1");

            // 자기 자신 (A) 은 무시, 다른 pod (B) 은 받아야 함.
            Awaitility.await().atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(evictedAtB).containsExactly("sku-A1"));
            assertThat(evictedAtA).isEmpty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            listenerA.stop();
            listenerB.stop();
        }
    }

    private RedisMessageListenerContainer newListenerContainer(CacheInvalidationSubscriber subscriber) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(cf);
        container.afterPropertiesSet();
        container.addMessageListener(subscriber, new ChannelTopic(CHANNEL));
        container.start();
        return container;
    }
}
