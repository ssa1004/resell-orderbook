package com.example.market.application.command

import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.ListingId

@JvmRecord
data class CancelListingCommand(val requestor: UserId, val listingId: ListingId)
