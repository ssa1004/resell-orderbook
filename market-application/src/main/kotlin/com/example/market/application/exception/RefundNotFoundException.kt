package com.example.market.application.exception

import com.example.market.domain.settlement.RefundId

class RefundNotFoundException(id: RefundId) : RuntimeException("refund not found: $id")
