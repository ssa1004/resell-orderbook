package com.example.market.application.exception

import com.example.market.domain.trading.BidId

class BidNotFoundException(id: BidId) : RuntimeException("bid not found: $id")
