package com.example.market.application.port.out;

import java.time.Duration;

/**
 * Token bucket 기반 분산 rate limiter (ADR-0020).
 *
 * <p><b>Token bucket 알고리즘</b>: 통(bucket) 에 토큰이 매 {@code refillInterval} 마다 {@code refillTokens}
 * 개씩 천천히 채워지고, 요청 1건이 토큰 1개를 소비. 토큰이 없으면 거부 (429). 통의 최대 용량은
 * {@code capacity} — burst (일시적 트래픽 폭증) 를 일정량 까지 허용 + 평균 rate 는 refill rate 로
 * 제한. 응답 형식 (`429 Too Many Requests` + `Retry-After`) 은 RFC 6585 / RFC 7231 표준.</p>
 *
 * <p><b>왜 sliding window 가 아닌 token bucket?</b> sliding window 는 정확하지만 메모리 더 쓰고
 * 단발 burst 처리가 어색. token bucket 은 *count + last refill timestamp* 두 값만 있으면 됨 → Redis
 * 에 hash 1개. 인기 SKU burst 도 통 capacity 만큼은 자연스럽게 허용.</p>
 *
 * <p><b>Atomicity</b>: 구현체는 *원자적* 이어야 함 (Redis 의 경우 Lua EVAL 로 read-decide-write 를
 * 한 번에). 그렇지 않으면 동시에 도착한 두 요청이 모두 "토큰 1개 남음" 을 보고 둘 다 통과시키는
 * race condition 이 생길 수 있다.</p>
 *
 * <p><b>Failure mode</b>: Redis 다운 등 limiter 자체가 실패하면 *fail-open* 권장 (가용성 우선) —
 * 한정판 구매 burst 보다 "전체 사용자가 503" 이 더 심한 사고. 단, 보안 민감 endpoint 는
 * fail-closed 가 적합 — 정책에 따라 구현체 선택.</p>
 */
public interface TokenBucketRateLimiter {

    /**
     * 한 토큰 소비 시도.
     *
     * @param key            bucket 키 — 보통 {@code "<userId>:<endpoint>"} 형태
     * @param capacity       통 최대 용량 (= 허용 burst 크기)
     * @param refillTokens   {@code refillInterval} 마다 채워지는 토큰 수
     * @param refillInterval refill 간격 — 보통 1초
     * @return 허용 여부 + 거부 시 다시 시도 가능한 시간 (Retry-After 헤더용)
     */
    Decision tryConsume(String key, int capacity, int refillTokens, Duration refillInterval);

    /**
     * @param allowed     true 면 요청 진행 가능, false 면 거부 (429)
     * @param retryAfter  거부 시 다음 토큰이 채워질 때까지 대기 시간. 허용 시 ZERO.
     * @param remaining   소비 후 남은 토큰 수 — 응답 헤더 {@code X-RateLimit-Remaining} 등에 활용 가능
     */
    record Decision(boolean allowed, Duration retryAfter, int remaining) {
        public static Decision allowed(int remaining) {
            return new Decision(true, Duration.ZERO, remaining);
        }
        public static Decision rejected(Duration retryAfter) {
            return new Decision(false, retryAfter, 0);
        }
    }
}
