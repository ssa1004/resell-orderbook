package com.example.market.application.command;

import com.example.market.domain.trading.TradeId;

public record AuthorizePaymentCommand(TradeId tradeId) {}
