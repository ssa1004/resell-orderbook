package com.example.market.adapter.out.cache;

import com.example.market.application.port.out.MarketStatsCache;
import com.example.market.domain.catalog.SkuId;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.util.UUID;

/**
 * 캐시 invalidate broadcast 인프라 — publisher + Redis pub/sub listener container (ADR-0022).
 *
 * <p>{@code market.cache.redis-enabled=true} + {@code market.cache.invalidation-broadcast.enabled=true}
 * 일 때 활성. 단일 인스턴스 dev 환경에서는 broadcast 가 무의미하므로 같이 비활성.</p>
 *
 * <h3>sourceId</h3>
 *
 * <p>K8s pod 식별자가 있으면 그것 (env {@code POD_NAME}), 없으면 PID + random UUID prefix. 같은
 * pod 안에서 publish/subscribe 가 동시에 일어나는데, sourceId 비교로 자기 메시지를 무시하므로
 * pod 단위 유일성만 있으면 충분.</p>
 */
@Configuration
@ConditionalOnProperty(name = {"market.cache.redis-enabled", "market.cache.invalidation-broadcast.enabled"},
        havingValue = "true")
@Slf4j
public class CacheInvalidationConfiguration {

    public static final String DEFAULT_CHANNEL = "market:cache:invalidate:market-stats:v1";

    @Bean
    public CacheInvalidationPublisher cacheInvalidationPublisher(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            Clock clock,
            MarketStatsCache cache,
            @Value("${market.cache.invalidation-broadcast.channel:" + DEFAULT_CHANNEL + "}") String channel) {
        String sourceId = resolveSourceId();
        log.info("cache invalidation publisher 활성 channel={} sourceId={}", channel, sourceId);
        var publisher = new CacheInvalidationPublisher(redis, ensureJavaTime(objectMapper), channel, sourceId, clock);
        // 캐시 구현체에 setter 주입 — 도메인 라이트가 일어날 때 publish 호출되도록.
        if (cache instanceof TwoTierMarketStatsCache twoTier) {
            twoTier.setInvalidationPublisher(publisher);
        }
        return publisher;
    }

    /**
     * Redis pub/sub listener container — Spring 이 background thread 로 채널을 구독한다.
     */
    @Bean(destroyMethod = "stop")
    public RedisMessageListenerContainer cacheInvalidationListenerContainer(
            RedisConnectionFactory connectionFactory,
            ObjectMapper objectMapper,
            CacheInvalidationPublisher publisher,
            MarketStatsCache cache,
            @Value("${market.cache.invalidation-broadcast.channel:" + DEFAULT_CHANNEL + "}") String channel) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        var subscriber = new CacheInvalidationSubscriber(
                ensureJavaTime(objectMapper),
                publisher.sourceId(),
                key -> {
                    if (cache instanceof TwoTierMarketStatsCache twoTier) {
                        twoTier.evictL1Only(SkuId.of(key));
                    } else {
                        cache.invalidate(SkuId.of(key));
                    }
                });
        container.addMessageListener(subscriber, new ChannelTopic(channel));
        log.info("cache invalidation listener 활성 channel={}", channel);
        return container;
    }

    /** Jackson 의 JavaTimeModule 이 등록되어 있지 않으면 등록 (Instant 등 직렬화 호환). */
    private static ObjectMapper ensureJavaTime(ObjectMapper mapper) {
        if (mapper.getRegisteredModuleIds().stream().anyMatch(id -> id.toString().contains("JavaTimeModule"))) {
            return mapper;
        }
        return mapper.copy().registerModule(new JavaTimeModule());
    }

    /**
     * pod 식별자 결정 — env POD_NAME (K8s) > HOSTNAME > PID + random UUID prefix.
     */
    private static String resolveSourceId() {
        String pod = System.getenv("POD_NAME");
        if (pod != null && !pod.isBlank()) return pod;
        String host = System.getenv("HOSTNAME");
        if (host != null && !host.isBlank()) return host;
        String pid = ManagementFactory.getRuntimeMXBean().getName();
        return pid + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
