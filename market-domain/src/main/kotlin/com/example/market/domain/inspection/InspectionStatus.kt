package com.example.market.domain.inspection

enum class InspectionStatus {
    PENDING,        // 검수 대기
    IN_PROGRESS,    // 담당자 배정, 진행 중
    DECIDED,        // 결과 기록 완료
}
