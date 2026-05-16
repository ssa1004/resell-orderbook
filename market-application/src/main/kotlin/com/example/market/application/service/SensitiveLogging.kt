package com.example.market.application.service

import java.math.BigDecimal

/**
 * 로그에 들어가는 민감 식별자(송장번호, 외부 결제 ID 등)와 송금 금액을 짧게 가리는 헬퍼.
 *
 * 전체 값을 그대로 INFO 로그에 박으면 운영 로그 수집 단계에서 PII / 배송 정보 / 송금 금액이
 * 평문으로 흩어진다. 운영 추적에 필요한 최소한 — 앞 4자리 + 길이, 또는 자릿수 — 만 남긴다.
 * 분쟁 조사 시 거래 ID 로 DB 에서 원본을 조회.
 */
object SensitiveLogging {

    /**
     * 송장번호 같은 짧은 식별자 마스킹. 짧으면 길이만, 길면 앞 4자리 + 별표.
     * 예: "AB1234567890" → "AB12***(12)", "ABC" → "***(3)".
     */
    @JvmStatic
    fun mask(value: String?): String {
        if (value == null) return "null"
        val len = value.length
        if (len <= 4) return "***($len)"
        return value.substring(0, 4) + "***($len)"
    }

    /**
     * 금액 마스킹 — 정확한 값은 가리고 자릿수만 남긴다. 분쟁 조사 시 DB 에서 정확한 금액 조회.
     * 예: 1234500 → "***(7-digit)", 0 → "***(1-digit)", null → "null".
     *
     * 자릿수는 정수부 길이 — 소수부와 trailing zero 영향 없이 계산.
     */
    @JvmStatic
    fun maskAmount(amount: BigDecimal?): String {
        if (amount == null) return "null"
        val abs = amount.abs()
        // 0 은 자리수 1로 본다 ("0" 한 자리)
        val digits = if (abs.signum() == 0) 1 else abs.toBigInteger().toString().length
        val sign = if (amount.signum() < 0) "-" else ""
        return sign + "***($digits-digit)"
    }
}
