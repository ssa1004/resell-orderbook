package com.example.market.adapter.web.ratelimit

import java.lang.annotation.Inherited

/**
 * Token bucket rate limit 적용 (ADR-0020). controller method 에 부착.
 *
 * 키 전략은 사용자(JWT/X-User-Id) + endpoint. 운영 정책상 user-tier 별로 capacity 차등이 필요하면
 * 별도 attribute 추가 (예: `tier=Tier.VIP`).
 *
 * Redis 미사용 dev 환경에서는 in-memory 구현이 자동 활성 (인스턴스 1대라 정확).
 *
 * @property capacity   통의 최대 용량 (= 허용 burst)
 * @property refillTokens   refillIntervalMs 마다 채워지는 토큰 수
 * @property refillIntervalMs   refill 간격 (ms). 기본 1000 = 1초
 * @property keyStrategy   bucket 키 결정 — 사용자별이 기본
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Inherited
annotation class RateLimited(
    val capacity: Int,
    val refillTokens: Int,
    val refillIntervalMs: Long = 1_000L,
    val keyStrategy: KeyStrategy = KeyStrategy.PER_USER,
) {
    enum class KeyStrategy {
        /** `userId:METHOD path` — 호가 폭주 보호의 기본. */
        PER_USER,

        /** 익명 트래픽까지 합쳐 IP + endpoint 로. fallback. */
        PER_IP,
    }
}
