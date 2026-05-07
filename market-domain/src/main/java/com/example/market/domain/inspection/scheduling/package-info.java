/**
 * 검수 슬롯 / 예약 도메인.
 *
 * <p>기존 {@link com.example.market.domain.inspection} 은 *검수 작업 자체* (PENDING → DECIDED) 를 다루고,
 * 이 패키지는 그 *전 단계* — 셀러가 *언제 어느 센터에 가져갈지* 예약하는 시스템. Kream 의
 * "검수 일정 예약" 화면의 백엔드. capacity 는 advisory lock + COUNT 로 over-booking 방지.</p>
 */
@org.springframework.modulith.NamedInterface("inspection-scheduling")
package com.example.market.domain.inspection.scheduling;
