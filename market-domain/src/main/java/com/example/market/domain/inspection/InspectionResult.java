package com.example.market.domain.inspection;

import java.util.Objects;

/**
 * 검수 결과 — outcome (PASS/FAIL) + 사유 + 비고.
 */
public record InspectionResult(InspectionOutcome outcome, String reason, String note) {

    public InspectionResult {
        Objects.requireNonNull(outcome);
        if (outcome == InspectionOutcome.FAIL) {
            Objects.requireNonNull(reason, "reason required for FAIL");
            if (reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
        }
    }

    public static InspectionResult pass(String note) {
        return new InspectionResult(InspectionOutcome.PASS, null, note);
    }

    public static InspectionResult fail(String reason, String note) {
        return new InspectionResult(InspectionOutcome.FAIL, reason, note);
    }
}
