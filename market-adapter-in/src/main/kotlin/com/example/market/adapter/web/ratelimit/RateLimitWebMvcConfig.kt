package com.example.market.adapter.web.ratelimit

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * [RateLimitInterceptor] 등록 — 모든 `/api/v1/{double-asterisk}` 경로에 적용.
 *
 * 실제 적용 여부는 method 의 `@RateLimited` 가 결정. interceptor 자체는 path 매칭만
 * 하고 annotation 없는 method 는 즉시 통과.
 */
@Configuration
class RateLimitWebMvcConfig(
    private val interceptor: RateLimitInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor)
            .addPathPatterns("/api/v1/**")
    }
}
