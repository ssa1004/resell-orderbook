package com.example.market.adapter.out.persistence.jpa

import com.example.market.adapter.out.persistence.jpa.mapper.BidJpaMapper
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataBidRepository
import com.example.market.application.port.out.BidRepository
import com.example.market.domain.trading.Bid
import com.example.market.domain.trading.BidId
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
class JpaBidRepositoryAdapter(
    private val jpa: SpringDataBidRepository,
) : BidRepository {

    override fun save(bid: Bid) {
        jpa.save(BidJpaMapper.toEntity(bid))
    }

    override fun findById(id: BidId): Optional<Bid> =
        jpa.findById(id.value).map(BidJpaMapper::toDomain)
}
