package com.example.market.adapter.out.cache

import com.example.market.application.port.out.MarketStatsCache
import com.example.market.domain.catalog.SkuId
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.lang.management.ManagementFactory
import java.time.Clock
import java.util.UUID
import java.util.function.Consumer as JConsumer

/**
 * 캐시 invalidate broadcast 인프라 — publisher + Redis pub/sub listener container (ADR-0022).
 *
 * `market.cache.redis-enabled=true` + `market.cache.invalidation-broadcast.enabled=true`
 * 일 때 활성. 단일 인스턴스 dev 환경에서는 broadcast 가 무의미하므로 같이 비활성.
 *
 * ### sourceId
 *
 * K8s pod 식별자가 있으면 그것 (env `POD_NAME`), 없으면 PID + random UUID prefix. 같은
 * pod 안에서 publish/subscribe 가 동시에 일어나는데, sourceId 비교로 자기 메시지를 무시하므로
 * pod 단위 유일성만 있으면 충분.
 */
@Configuration
@ConditionalOnProperty(
    name = ["market.cache.redis-enabled", "market.cache.invalidation-broadcast.enabled"],
    havingValue = "true",
)
class CacheInvalidationConfiguration {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun cacheInvalidationPublisher(
        redis: StringRedisTemplate,
        objectMapper: ObjectMapper,
        clock: Clock,
        cache: MarketStatsCache,
        @Value("\${market.cache.invalidation-broadcast.channel:$DEFAULT_CHANNEL}") channel: String,
    ): CacheInvalidationPublisher {
        val sourceId = resolveSourceId()
        log.info("cache invalidation publisher 활성 channel={} sourceId={}", channel, sourceId)
        val publisher = CacheInvalidationPublisher(
            redis, ensureJavaTime(objectMapper), channel, sourceId, clock,
        )
        // 캐시 구현체에 setter 주입 — 도메인 라이트가 일어날 때 publish 호출되도록.
        if (cache is TwoTierMarketStatsCache) {
            cache.setInvalidationPublisher(publisher)
        }
        return publisher
    }

    /**
     * Redis pub/sub listener container — Spring 이 background thread 로 채널을 구독한다.
     */
    @Bean(destroyMethod = "stop")
    fun cacheInvalidationListenerContainer(
        connectionFactory: RedisConnectionFactory,
        objectMapper: ObjectMapper,
        publisher: CacheInvalidationPublisher,
        cache: MarketStatsCache,
        @Value("\${market.cache.invalidation-broadcast.channel:$DEFAULT_CHANNEL}") channel: String,
    ): RedisMessageListenerContainer {
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(connectionFactory)

        val onInvalidate = JConsumer<String> { key ->
            if (cache is TwoTierMarketStatsCache) {
                cache.evictL1Only(SkuId(UUID.fromString(key)))
            } else {
                cache.invalidate(SkuId(UUID.fromString(key)))
            }
        }
        val subscriber = CacheInvalidationSubscriber(
            ensureJavaTime(objectMapper),
            publisher.sourceId(),
            onInvalidate,
        )
        container.addMessageListener(subscriber, ChannelTopic(channel))
        log.info("cache invalidation listener 활성 channel={}", channel)
        return container
    }

    /** Jackson 의 JavaTimeModule 이 등록되어 있지 않으면 등록 (Instant 등 직렬화 호환). */
    private fun ensureJavaTime(mapper: ObjectMapper): ObjectMapper {
        if (mapper.registeredModuleIds.any { it.toString().contains("JavaTimeModule") }) {
            return mapper
        }
        return mapper.copy().registerModule(JavaTimeModule())
    }

    /**
     * pod 식별자 결정 — env POD_NAME (K8s) > HOSTNAME > PID + random UUID prefix.
     */
    private fun resolveSourceId(): String {
        val pod = System.getenv("POD_NAME")
        if (!pod.isNullOrBlank()) return pod
        val host = System.getenv("HOSTNAME")
        if (!host.isNullOrBlank()) return host
        val pid = ManagementFactory.getRuntimeMXBean().name
        return pid + "-" + UUID.randomUUID().toString().substring(0, 8)
    }

    companion object {
        const val DEFAULT_CHANNEL = "market:cache:invalidate:market-stats:v1"
    }
}
