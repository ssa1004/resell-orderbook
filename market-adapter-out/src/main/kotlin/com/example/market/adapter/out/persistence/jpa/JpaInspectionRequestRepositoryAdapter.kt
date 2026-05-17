package com.example.market.adapter.out.persistence.jpa

import com.example.market.adapter.out.persistence.jpa.mapper.InspectionRequestJpaMapper
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataInspectionRequestRepository
import com.example.market.application.port.out.InspectionRequestRepository
import com.example.market.domain.inspection.InspectionRequest
import com.example.market.domain.inspection.InspectionRequestId
import com.example.market.domain.trading.TradeId
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
class JpaInspectionRequestRepositoryAdapter(
    private val jpa: SpringDataInspectionRequestRepository,
) : InspectionRequestRepository {

    override fun save(request: InspectionRequest) {
        jpa.save(InspectionRequestJpaMapper.toEntity(request))
    }

    override fun findById(id: InspectionRequestId): Optional<InspectionRequest> =
        jpa.findById(id.value).map(InspectionRequestJpaMapper::toDomain)

    override fun findByTradeId(tradeId: TradeId): Optional<InspectionRequest> =
        jpa.findByTradeId(tradeId.value).map(InspectionRequestJpaMapper::toDomain)
}
