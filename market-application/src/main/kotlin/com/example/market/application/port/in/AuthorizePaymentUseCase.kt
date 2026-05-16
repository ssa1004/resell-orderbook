package com.example.market.application.port.`in`

import com.example.market.application.command.AuthorizePaymentCommand
import com.example.market.domain.trading.Trade

/**
 * TradeMatched 이벤트 컨슈머가 호출. PG.authorize → Trade.authorizePayment / cancelOnPaymentFailure.
 */
interface AuthorizePaymentUseCase {
    fun authorize(command: AuthorizePaymentCommand): Trade
}
