package com.example.market.application.port.out

import com.example.market.domain.trading.Listing
import com.example.market.domain.trading.ListingId
import java.util.Optional

interface ListingRepository {
    fun save(listing: Listing)
    fun findById(id: ListingId): Optional<Listing>
}
