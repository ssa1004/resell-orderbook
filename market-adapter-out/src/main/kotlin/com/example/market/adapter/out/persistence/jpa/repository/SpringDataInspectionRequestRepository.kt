package com.example.market.adapter.out.persistence.jpa.repository

import com.example.market.adapter.out.persistence.jpa.entity.InspectionRequestJpaEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface SpringDataInspectionRequestRepository : JpaRepository<InspectionRequestJpaEntity, UUID> {
    fun findByTradeId(tradeId: UUID): Optional<InspectionRequestJpaEntity>
}
