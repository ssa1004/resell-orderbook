package com.example.market.application.exception

import com.example.market.domain.trading.TradeId

class TradeNotFoundException(id: TradeId) : RuntimeException("trade not found: $id")
