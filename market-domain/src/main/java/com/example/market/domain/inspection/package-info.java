/**
 * Inspection — 검수센터의 검수 요청/결과 도메인.
 *
 * <p>판매자가 검수센터로 발송하면 InspectionRequest 가 생성되고, 검수 담당자가 결과(PASS/FAIL)를
 * 기록하면 InspectionResult 가 만들어진다. 결과에 따라 Trade 가 INSPECTION_PASSED / FAILED 로 전이.</p>
 */
@org.springframework.modulith.NamedInterface("inspection")
package com.example.market.domain.inspection;
