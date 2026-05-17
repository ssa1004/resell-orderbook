package com.example.market.adapter.out.persistence.jpa

import com.example.market.adapter.out.persistence.jpa.mapper.ListingJpaMapper
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataListingRepository
import com.example.market.application.port.out.ListingRepository
import com.example.market.domain.trading.Listing
import com.example.market.domain.trading.ListingId
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
class JpaListingRepositoryAdapter(
    private val jpa: SpringDataListingRepository,
) : ListingRepository {

    override fun save(listing: Listing) {
        jpa.save(ListingJpaMapper.toEntity(listing))
    }

    override fun findById(id: ListingId): Optional<Listing> =
        jpa.findById(id.value).map(ListingJpaMapper::toDomain)
}
