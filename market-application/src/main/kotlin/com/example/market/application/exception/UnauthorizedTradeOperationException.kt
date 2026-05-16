package com.example.market.application.exception

import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.TradeId

/**
 * Trade 의 sellerId/buyerId 가 아닌 사용자가 라이프사이클 메서드를 호출했을 때.
 * Adapter-in 이 HTTP 403 으로 매핑.
 */
class UnauthorizedTradeOperationException(tradeId: TradeId, requestor: UserId, op: String) :
    RuntimeException("trade $tradeId — requestor $requestor not authorized for $op")
