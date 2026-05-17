package com.example.market.application.port.out

import java.time.Duration

/**
 * 운영자 콘솔 (admin) 전용 rate limiter — 사용자 API 의 [TokenBucketRateLimiter] 와
 * 분리한다. ([ADR-0020] 의 token bucket 자체는 같은 모델, 키 네임스페이스만 다르다.)
 *
 * 별도 인터페이스를 두는 이유:
 *
 * 1. **scope 분리**: read (`GET /admin/dlq/...`) / write (`POST .../replay|discard`) / bulk
 *    (`POST .../bulk-*`) 가 서로 다른 RPS 정책을 가져야 한다. 일반 사용자 token bucket 에
 *    같이 묶으면 정책 혼선.
 * 2. **fail-closed 옵션**: 일반 사용자 limiter 는 fail-open 이지만 (가용성 우선), admin 의
 *    bulk 액션은 운영자가 잘못 호출했을 때 PG 가 두 번 가는 사고가 크다 — 어댑터 구현체가
 *    Redis 장애 시 fail-closed 선택지를 가질 수 있도록 별 인터페이스.
 * 3. **별 키 prefix**: `admin:dlq:<ip>:<scope>` 처럼 prefix 가 다르다.
 *
 * 구현체는 분당 token 으로 동작 — `tryAcquire("dlq.read", "<ip>")` 가 [Decision] 반환.
 *
 * @see TokenBucketRateLimiter
 */
interface AdminRateLimiter {

    /**
     * @param scope  운영 의미 단위 — "dlq.read" / "dlq.write" / "dlq.bulk" 등
     * @param actorKey 운영자 식별자 (보통 IP — 한 명의 운영자가 자기 RPS 안에서만 호출)
     * @return       허용 / 거부 + Retry-After
     */
    fun tryAcquire(scope: String, actorKey: String): Decision

    @JvmRecord
    data class Decision(val allowed: Boolean, val retryAfter: Duration) {
        companion object {
            // 이름이 `allow` / `reject` 인 이유: `allowed` 는 record accessor 와 충돌해 Java
            // 컴파일러가 호출 모호성으로 거부. 따라서 동사 형태로 분리.
            @JvmStatic fun allow(): Decision = Decision(true, Duration.ZERO)

            @JvmStatic
            fun reject(retryAfter: Duration): Decision = Decision(false, retryAfter)
        }
    }
}
