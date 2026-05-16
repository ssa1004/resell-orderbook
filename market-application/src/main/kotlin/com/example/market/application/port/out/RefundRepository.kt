package com.example.market.application.port.out

import com.example.market.domain.settlement.Refund
import com.example.market.domain.settlement.RefundId
import com.example.market.domain.trading.TradeId
import java.util.Optional

interface RefundRepository {
    fun save(refund: Refund)
    fun findById(id: RefundId): Optional<Refund>
    fun findByTradeId(tradeId: TradeId): Optional<Refund>
}
