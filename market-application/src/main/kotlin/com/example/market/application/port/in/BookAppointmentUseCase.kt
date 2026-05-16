package com.example.market.application.port.`in`

import com.example.market.application.command.BookAppointmentCommand
import com.example.market.domain.inspection.scheduling.InspectionAppointment

interface BookAppointmentUseCase {
    fun book(command: BookAppointmentCommand): InspectionAppointment
}
