package com.example.market.application.port.out

import com.example.market.domain.settlement.Payout
import com.example.market.domain.settlement.PayoutId
import com.example.market.domain.trading.TradeId
import java.util.Optional

interface PayoutRepository {
    fun save(payout: Payout)
    fun findById(id: PayoutId): Optional<Payout>
    fun findByTradeId(tradeId: TradeId): Optional<Payout>
}
