package com.example.market.adapter.out.persistence.jpa

import com.example.market.adapter.out.persistence.jpa.mapper.ListingJpaMapper
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataListingRepository
import com.example.market.application.port.out.StaleListingScanner
import com.example.market.domain.trading.Listing
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class JpaStaleListingScanner(
    private val jpa: SpringDataListingRepository,
) : StaleListingScanner {

    @Transactional(readOnly = true)
    override fun findStaleActive(cutoff: Instant, batchSize: Int): List<Listing> =
        jpa.findStaleActive(cutoff, PageRequest.of(0, batchSize))
            .map(ListingJpaMapper::toDomain)
}
