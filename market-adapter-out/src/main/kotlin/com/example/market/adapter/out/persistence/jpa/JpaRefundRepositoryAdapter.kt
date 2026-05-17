package com.example.market.adapter.out.persistence.jpa

import com.example.market.adapter.out.persistence.jpa.mapper.RefundJpaMapper
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataRefundRepository
import com.example.market.application.port.out.RefundRepository
import com.example.market.domain.settlement.Refund
import com.example.market.domain.settlement.RefundId
import com.example.market.domain.trading.TradeId
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
class JpaRefundRepositoryAdapter(
    private val jpa: SpringDataRefundRepository,
) : RefundRepository {

    override fun save(refund: Refund) {
        jpa.save(RefundJpaMapper.toEntity(refund))
    }

    override fun findById(id: RefundId): Optional<Refund> =
        jpa.findById(id.value).map(RefundJpaMapper::toDomain)

    override fun findByTradeId(tradeId: TradeId): Optional<Refund> =
        jpa.findByTradeId(tradeId.value).map(RefundJpaMapper::toDomain)
}
