package com.example.market.application.port.out

import com.example.market.domain.trading.Bid
import com.example.market.domain.trading.BidId
import java.util.Optional

interface BidRepository {
    fun save(bid: Bid)
    fun findById(id: BidId): Optional<Bid>
}
