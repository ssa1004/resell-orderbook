package com.example.market.application.port.`in`

import com.example.market.application.command.RecordInspectionResultCommand
import com.example.market.domain.inspection.InspectionRequest

interface RecordInspectionResultUseCase {
    fun record(command: RecordInspectionResultCommand): InspectionRequest
}
