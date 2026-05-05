package com.example.market.adapter.out.persistence.jpa;

import com.example.market.adapter.out.persistence.jpa.mapper.InspectionRequestJpaMapper;
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataInspectionRequestRepository;
import com.example.market.application.port.out.InspectionRequestRepository;
import com.example.market.domain.inspection.InspectionRequest;
import com.example.market.domain.inspection.InspectionRequestId;
import com.example.market.domain.trading.TradeId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaInspectionRequestRepositoryAdapter implements InspectionRequestRepository {

    private final SpringDataInspectionRequestRepository jpa;

    @Override
    public void save(InspectionRequest request) {
        jpa.save(InspectionRequestJpaMapper.toEntity(request));
    }

    @Override
    public Optional<InspectionRequest> findById(InspectionRequestId id) {
        return jpa.findById(id.value()).map(InspectionRequestJpaMapper::toDomain);
    }

    @Override
    public Optional<InspectionRequest> findByTradeId(TradeId tradeId) {
        return jpa.findByTradeId(tradeId.value()).map(InspectionRequestJpaMapper::toDomain);
    }
}
