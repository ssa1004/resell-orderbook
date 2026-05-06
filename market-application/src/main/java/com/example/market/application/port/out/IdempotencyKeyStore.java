package com.example.market.application.port.out;

/**
 * 멱등성 키 저장소. 사용자 요청의 중복 차단 — Redis NX (SET key value NX EX ttl).
 *
 * <p>같은 키 재요청 시 {@link DuplicateRequestException} → Adapter-in 이 HTTP 409 로 매핑.
 * 응답 캐시는 일단 미구현 (ADR — KISS, 클라이언트 retry 책임).</p>
 */
public interface IdempotencyKeyStore {

    void acquireOrThrow(String key);

    class DuplicateRequestException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DuplicateRequestException(String key) {
            super("duplicate request key: " + key);
        }
    }
}
