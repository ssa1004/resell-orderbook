package com.example.market.application.command;

import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.TradeId;

public record CompleteTradeCommand(UserId requestor, TradeId tradeId) {}
