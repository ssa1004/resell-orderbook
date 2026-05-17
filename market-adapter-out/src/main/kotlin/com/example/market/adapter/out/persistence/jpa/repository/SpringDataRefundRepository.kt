package com.example.market.adapter.out.persistence.jpa.repository

import com.example.market.adapter.out.persistence.jpa.entity.RefundJpaEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface SpringDataRefundRepository : JpaRepository<RefundJpaEntity, UUID> {
    fun findByTradeId(tradeId: UUID): Optional<RefundJpaEntity>
}
