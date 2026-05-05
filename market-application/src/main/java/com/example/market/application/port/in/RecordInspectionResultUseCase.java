package com.example.market.application.port.in;

import com.example.market.application.command.RecordInspectionResultCommand;
import com.example.market.domain.inspection.InspectionRequest;

public interface RecordInspectionResultUseCase {
    InspectionRequest record(RecordInspectionResultCommand command);
}
