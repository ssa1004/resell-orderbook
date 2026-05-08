package com.example.market.application.service;

/**
 * 로그에 들어가는 민감 식별자(송장번호, 외부 결제 ID 등)를 짧게 가리는 헬퍼.
 *
 * <p>전체 값을 그대로 INFO 로그에 박으면 운영 로그 수집 단계에서 PII/배송 정보가 평문으로
 * 흩어진다. 운영 추적에 필요한 최소한 — 앞 4자리 + 길이 — 만 남긴다. 분쟁 조사 시
 * 거래 ID 로 DB 에서 원본을 조회.</p>
 */
final class SensitiveLogging {

    private SensitiveLogging() {}

    /**
     * 송장번호 같은 짧은 식별자 마스킹. 짧으면 길이만, 길면 앞 4자리 + 별표.
     * 예: "AB1234567890" → "AB12***(12)", "ABC" → "***(3)".
     */
    static String mask(String value) {
        if (value == null) return "null";
        int len = value.length();
        if (len <= 4) return "***(" + len + ")";
        return value.substring(0, 4) + "***(" + len + ")";
    }
}
