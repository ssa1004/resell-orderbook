package com.example.market.application.exception

import com.example.market.domain.inspection.InspectionRequestId

class InspectionRequestNotFoundException(id: InspectionRequestId) :
    RuntimeException("inspection request not found: $id")
