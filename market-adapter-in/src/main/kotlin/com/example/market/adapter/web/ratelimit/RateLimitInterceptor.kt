package com.example.market.adapter.web.ratelimit

import com.example.market.adapter.web.auth.CallerExtractor
import com.example.market.application.port.out.TokenBucketRateLimiter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import java.time.Duration

/**
 * [RateLimited] 가 붙은 controller method 호출 직전에 token bucket 검사 (ADR-0020).
 *
 * 흐름:
 * 1. handler 가 method 가 아니면 통과 (정적 자원 등)
 * 2. method 의 `@RateLimited` 미존재 시 통과
 * 3. 존재 시 limiter.tryConsume(...) — 거부면 429 + Retry-After 헤더, 통과면 응답 헤더에
 *    `X-RateLimit-Remaining` 등 부착
 *
 * 왜 AOP 가 아닌 HandlerInterceptor 인가: AspectJ weaver 추가 의존 없이 Spring MVC 만으로
 * 처리 가능. controller 진입 직전이 정확한 적용 시점이라 의미상 더 명확. (다른 layer 에서도 적용
 * 필요하면 그땐 AOP 로 확장.)
 */
@Component
class RateLimitInterceptor(
    private val limiter: TokenBucketRateLimiter,
    private val callerExtractor: CallerExtractor,
) : HandlerInterceptor {

    companion object {
        private val log = LoggerFactory.getLogger(RateLimitInterceptor::class.java)
        private const val HEADER_LIMIT = "X-RateLimit-Limit"
        private const val HEADER_REMAINING = "X-RateLimit-Remaining"
        private const val HEADER_RETRY_AFTER = "Retry-After"
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (handler !is HandlerMethod) return true
        val annotation = handler.getMethodAnnotation(RateLimited::class.java) ?: return true

        val key = bucketKey(annotation, request, handler)
        val refillInterval = Duration.ofMillis(annotation.refillIntervalMs)
        val decision = limiter.tryConsume(
            key, annotation.capacity, annotation.refillTokens, refillInterval,
        )

        response.setHeader(HEADER_LIMIT, annotation.capacity.toString())

        if (!decision.allowed()) {
            // RFC 7231 의 Retry-After: delta-seconds 형태 (초 단위). retry_after_ms 는 올림.
            val retrySec = (decision.retryAfter().toMillis() + 999L) / 1000L
            response.setHeader(HEADER_RETRY_AFTER, retrySec.toString())
            response.setHeader(HEADER_REMAINING, "0")
            response.status = 429   // HttpStatus.TOO_MANY_REQUESTS
            response.contentType = "application/problem+json"
            response.writer.write(
                """{"type":"about:blank","title":"Too Many Requests","status":429,""" +
                    """"detail":"rate limit exceeded — retry after ${retrySec}s",""" +
                    """"retryAfterSec":$retrySec}""",
            )
            log.info("rate limit 차단 key={} retryAfter={}ms", key, decision.retryAfter().toMillis())
            return false
        }

        response.setHeader(HEADER_REMAINING, decision.remaining().toString())
        return true
    }

    private fun bucketKey(
        annotation: RateLimited,
        request: HttpServletRequest,
        handler: HandlerMethod,
    ): String {
        // endpoint 식별자 — controller 의 method 시그니처. URI 보다 안정적 (path variable 영향 없음).
        val endpoint = "${handler.beanType.simpleName}#${handler.method.name}"
        val identity = when (annotation.keyStrategy) {
            RateLimited.KeyStrategy.PER_USER -> userId(request)
            RateLimited.KeyStrategy.PER_IP -> clientIp(request)
        }
        return "$identity:$endpoint"
    }

    private fun userId(request: HttpServletRequest): String {
        // 정상 흐름: SecurityContext 에서 JWT principal → CallerExtractor 로 userId 추출.
        val auth = SecurityContextHolder.getContext().authentication
        val jwt = auth?.principal as? Jwt
        return try {
            callerExtractor.from(jwt).userId.value
        } catch (_: Exception) {
            // jwt 비활성 / 익명 — IP 로 fallback (anonymous 동안 한 사람이 모든 토큰을 다 쓰지 않게).
            "anon-" + clientIp(request)
        }
    }

    private fun clientIp(request: HttpServletRequest): String {
        val xff = request.getHeader("X-Forwarded-For")
        if (!xff.isNullOrBlank()) {
            // XFF 는 콤마로 구분된 chain — 가장 왼쪽이 원본.
            return xff.substringBefore(',').trim()
        }
        return request.remoteAddr ?: "unknown"
    }
}
