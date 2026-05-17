package com.example.market.adapter.out.persistence.jpa

import com.example.market.adapter.out.persistence.jpa.mapper.PayoutJpaMapper
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataPayoutRepository
import com.example.market.application.port.out.PayoutRepository
import com.example.market.domain.settlement.Payout
import com.example.market.domain.settlement.PayoutId
import com.example.market.domain.trading.TradeId
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
class JpaPayoutRepositoryAdapter(
    private val jpa: SpringDataPayoutRepository,
) : PayoutRepository {

    override fun save(payout: Payout) {
        jpa.save(PayoutJpaMapper.toEntity(payout))
    }

    override fun findById(id: PayoutId): Optional<Payout> =
        jpa.findById(id.value).map(PayoutJpaMapper::toDomain)

    override fun findByTradeId(tradeId: TradeId): Optional<Payout> =
        jpa.findByTradeId(tradeId.value).map(PayoutJpaMapper::toDomain)
}
