package com.example.market.application.command;

import com.example.market.domain.inspection.InspectionRequestId;
import com.example.market.domain.shared.UserId;

public record AssignInspectorCommand(InspectionRequestId requestId, UserId inspectorId) {}
