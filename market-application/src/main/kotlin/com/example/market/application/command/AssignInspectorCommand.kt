package com.example.market.application.command

import com.example.market.domain.inspection.InspectionRequestId
import com.example.market.domain.shared.UserId

@JvmRecord
data class AssignInspectorCommand(val requestId: InspectionRequestId, val inspectorId: UserId)
