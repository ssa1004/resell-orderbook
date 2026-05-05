package com.example.market.application.port.out;

import com.example.market.domain.inspection.InspectionRequest;
import com.example.market.domain.inspection.InspectionRequestId;
import com.example.market.domain.trading.TradeId;

import java.util.Optional;

public interface InspectionRequestRepository {
    void save(InspectionRequest request);
    Optional<InspectionRequest> findById(InspectionRequestId id);
    Optional<InspectionRequest> findByTradeId(TradeId tradeId);
}
