package com.example.market.application.port.out

/**
 * 멱등성 키 저장소. 사용자 요청의 중복 차단 — Redis NX (SET key value NX EX ttl,
 * "키가 없을 때만 SET" 의 atomic 동작).
 *
 * 같은 키 재요청 시 [DuplicateRequestException] → Adapter-in 이 HTTP 409 로 매핑.
 * 응답 캐시는 일단 미구현 (ADR — KISS, 클라이언트 retry 책임).
 *
 * **release 의 용도**: acquire 직후 비즈니스 로직이 실패해 트랜잭션이 rollback 되면,
 * 키 점유는 Redis 에 남아 같은 키로 *legitimate retry 가 막힘* (TTL 기간 동안 DoS).
 * [com.example.market.application.service.IdempotentExecution] 이 트랜잭션 hook
 * 으로 자동 release 처리 — 호출자는 직접 release 부르지 않음.
 */
interface IdempotencyKeyStore {

    fun acquireOrThrow(key: String)

    /**
     * 점유 해제. acquire 후 트랜잭션 rollback 시 자동 release 용. 키가 없으면 no-op (멱등).
     */
    fun release(key: String)

    class DuplicateRequestException(key: String) :
        RuntimeException("duplicate request key: $key")
}
