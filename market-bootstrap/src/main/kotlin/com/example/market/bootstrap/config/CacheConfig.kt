package com.example.market.bootstrap.config

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * Caffeine L1 캐시 — 인기 상품 / Lowest ASK 등 hot 데이터.
 * Redis L2 는 redis-enabled=true 시 별도로 활성 (auto-config).
 */
@Configuration
@EnableCaching
open class CacheConfig {

    @Bean
    open fun cacheManager(): CacheManager {
        val cm = CaffeineCacheManager("products", "skus", "lowestAsk", "highestBid")
        cm.setCaffeine(
            Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(30)),
        )
        return cm
    }
}
