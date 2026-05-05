package com.example.market.application.port.in;

import com.example.market.application.command.RecordInspectionArrivalCommand;
import com.example.market.domain.inspection.InspectionRequest;

public interface RecordInspectionArrivalUseCase {
    InspectionRequest arrive(RecordInspectionArrivalCommand command);
}
