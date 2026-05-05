package com.example.market.application.command;

import com.example.market.domain.inspection.InspectionOutcome;
import com.example.market.domain.inspection.InspectionRequestId;
import com.example.market.domain.shared.UserId;

import java.util.List;

public record RecordInspectionResultCommand(
        UserId inspector,
        InspectionRequestId requestId,
        InspectionOutcome outcome,
        String reason,           // FAIL 시 필수
        String note,
        List<String> photoUrls
) {}
