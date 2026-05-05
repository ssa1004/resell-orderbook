package com.example.market.application.exception;

import com.example.market.domain.inspection.InspectionRequestId;

public class InspectionRequestNotFoundException extends RuntimeException {
    public InspectionRequestNotFoundException(InspectionRequestId id) {
        super("inspection request not found: " + id);
    }
}
