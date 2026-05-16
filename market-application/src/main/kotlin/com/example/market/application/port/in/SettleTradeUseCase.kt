package com.example.market.application.port.`in`

import com.example.market.domain.settlement.Payout
import com.example.market.domain.trading.TradeId

/**
 * TradeCompleted 이벤트 컨슈머. Payout.schedule + bankTransfer.send → Payout.send.
 */
interface SettleTradeUseCase {
    fun settle(tradeId: TradeId): Payout
}
