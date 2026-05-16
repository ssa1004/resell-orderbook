package com.example.market.application.command

import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.BidId

@JvmRecord
data class CancelBidCommand(val requestor: UserId, val bidId: BidId)
