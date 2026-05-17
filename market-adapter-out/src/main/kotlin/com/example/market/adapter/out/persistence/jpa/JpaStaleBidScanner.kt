package com.example.market.adapter.out.persistence.jpa

import com.example.market.adapter.out.persistence.jpa.mapper.BidJpaMapper
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataBidRepository
import com.example.market.application.port.out.StaleBidScanner
import com.example.market.domain.trading.Bid
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class JpaStaleBidScanner(
    private val jpa: SpringDataBidRepository,
) : StaleBidScanner {

    @Transactional(readOnly = true)
    override fun findStaleActive(cutoff: Instant, batchSize: Int): List<Bid> =
        jpa.findStaleActive(cutoff, PageRequest.of(0, batchSize))
            .map(BidJpaMapper::toDomain)
}
