package com.example.market.application.port.`in`

import com.example.market.application.command.RecordInspectionArrivalCommand
import com.example.market.domain.inspection.InspectionRequest

interface RecordInspectionArrivalUseCase {
    fun arrive(command: RecordInspectionArrivalCommand): InspectionRequest
}
