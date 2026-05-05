package com.example.market.application.command;

import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.TradeId;

public record RecordSellerShippingCommand(
        UserId requestor,    // 본인 거래만 가능
        TradeId tradeId,
        String trackingNumber
) {}
