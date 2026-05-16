package com.example.market.application.exception

import com.example.market.domain.settlement.PayoutId

class PayoutNotFoundException(id: PayoutId) : RuntimeException("payout not found: $id")
