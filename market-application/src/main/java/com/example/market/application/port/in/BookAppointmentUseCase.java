package com.example.market.application.port.in;

import com.example.market.application.command.BookAppointmentCommand;
import com.example.market.domain.inspection.scheduling.InspectionAppointment;

public interface BookAppointmentUseCase {
    InspectionAppointment book(BookAppointmentCommand command);
}
