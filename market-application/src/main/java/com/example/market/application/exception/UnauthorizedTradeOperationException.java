package com.example.market.application.exception;

import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.TradeId;

/**
 * Trade 의 sellerId/buyerId 가 아닌 사용자가 라이프사이클 메서드를 호출했을 때.
 * Adapter-in 이 HTTP 403 으로 매핑.
 */
public class UnauthorizedTradeOperationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnauthorizedTradeOperationException(TradeId tradeId, UserId requestor, String op) {
        super("trade " + tradeId + " — requestor " + requestor + " not authorized for " + op);
    }
}
