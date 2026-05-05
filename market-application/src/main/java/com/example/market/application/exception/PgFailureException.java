package com.example.market.application.exception;

/**
 * PG 응답이 명시적 실패 (CB OPEN, 카드 거절 등). Resilience4j fallback 으로 wrap 된 결과.
 */
public class PgFailureException extends RuntimeException {
    private final String errorCode;
    public PgFailureException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
    public String errorCode() { return errorCode; }
}
