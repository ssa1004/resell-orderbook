package com.example.market.adapter.web.dto

import com.example.market.application.command.AssignInspectorCommand
import com.example.market.application.command.RecordInspectionResultCommand
import com.example.market.domain.inspection.InspectionOutcome
import com.example.market.domain.inspection.InspectionRequest
import com.example.market.domain.inspection.InspectionRequestId
import com.example.market.domain.shared.UserId
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant

data class AssignInspectorRequest(@field:NotBlank val inspectorId: String) {
    fun toCommand(requestId: InspectionRequestId) =
        AssignInspectorCommand(requestId, UserId.of(inspectorId))
}

data class RecordInspectionResultRequest(
    @field:NotNull val outcome: InspectionOutcome,
    val reason: String?,
    val note: String?,
    val photoUrls: List<String> = emptyList(),
) {
    fun toCommand(inspector: UserId, requestId: InspectionRequestId) =
        RecordInspectionResultCommand(inspector, requestId, outcome, reason, note, photoUrls)
}

data class InspectionRequestResponse(
    val id: String,
    val tradeId: String,
    val status: String,
    val outcome: String?,
    val reason: String?,
    val note: String?,
    val inspectorId: String?,
    val photoUrls: List<String>,
    val requestedAt: Instant,
    val decidedAt: Instant?,
) {
    companion object {
        fun from(r: InspectionRequest) = InspectionRequestResponse(
            id = r.id().toString(),
            tradeId = r.tradeId().toString(),
            status = r.status().name,
            outcome = r.result()?.outcome()?.name,
            reason = r.result()?.reason(),
            note = r.result()?.note(),
            inspectorId = r.inspectorId()?.value(),
            photoUrls = r.photoUrls(),
            requestedAt = r.requestedAt(),
            decidedAt = r.decidedAt(),
        )
    }
}
