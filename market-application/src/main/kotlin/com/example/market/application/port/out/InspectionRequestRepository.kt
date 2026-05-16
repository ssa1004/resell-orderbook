package com.example.market.application.port.out

import com.example.market.domain.inspection.InspectionRequest
import com.example.market.domain.inspection.InspectionRequestId
import com.example.market.domain.trading.TradeId
import java.util.Optional

interface InspectionRequestRepository {
    fun save(request: InspectionRequest)
    fun findById(id: InspectionRequestId): Optional<InspectionRequest>
    fun findByTradeId(tradeId: TradeId): Optional<InspectionRequest>
}
