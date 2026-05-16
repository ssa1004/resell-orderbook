package com.example.market.application.command

import com.example.market.domain.inspection.InspectionOutcome
import com.example.market.domain.inspection.InspectionRequestId
import com.example.market.domain.shared.UserId

@JvmRecord
data class RecordInspectionResultCommand(
    val inspector: UserId,
    val requestId: InspectionRequestId,
    val outcome: InspectionOutcome,
    val reason: String?,           // FAIL 시 필수, PASS 시 null
    val note: String?,
    val photoUrls: List<String>,
)
