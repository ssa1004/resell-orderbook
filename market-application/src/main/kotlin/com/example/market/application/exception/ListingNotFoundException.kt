package com.example.market.application.exception

import com.example.market.domain.trading.ListingId

class ListingNotFoundException(id: ListingId) : RuntimeException("listing not found: $id")
