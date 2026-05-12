package com.example.market.domain.inspection

/**
 * 검수 결과 — outcome (PASS/FAIL) + 사유 + 비고.
 *
 * <p>Java/Kotlin 양쪽 호출자 호환 — 기존 record 의 accessor 명을 그대로 보존
 * ({@code outcome()}, {@code reason()}, {@code note()}).</p>
 */
data class InspectionResult(
    @get:JvmName("outcome") val outcome: InspectionOutcome,
    @get:JvmName("reason") val reason: String?,
    @get:JvmName("note") val note: String?,
) {

    init {
        if (outcome == InspectionOutcome.FAIL) {
            requireNotNull(reason) { "reason required for FAIL" }
            require(reason.isNotBlank()) { "reason must not be blank" }
        }
    }

    companion object {
        @JvmStatic
        fun pass(note: String?): InspectionResult =
            InspectionResult(InspectionOutcome.PASS, null, note)

        @JvmStatic
        fun fail(reason: String, note: String?): InspectionResult =
            InspectionResult(InspectionOutcome.FAIL, reason, note)
    }
}
