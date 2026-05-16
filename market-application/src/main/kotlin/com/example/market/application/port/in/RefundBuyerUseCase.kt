package com.example.market.application.port.`in`

import com.example.market.domain.settlement.Refund
import com.example.market.domain.trading.TradeId

/**
 * InspectionFailed 이벤트 컨슈머. PG.refund() → Refund.complete → Trade.closeAsFailedAfterRefund.
 */
interface RefundBuyerUseCase {
    fun refund(tradeId: TradeId): Refund
}
