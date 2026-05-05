package com.example.market.adapter.out.persistence.jpa;

import com.example.market.adapter.out.persistence.jpa.mapper.BidJpaMapper;
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataBidRepository;
import com.example.market.application.port.out.BidRepository;
import com.example.market.domain.trading.Bid;
import com.example.market.domain.trading.BidId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaBidRepositoryAdapter implements BidRepository {

    private final SpringDataBidRepository jpa;

    @Override
    public void save(Bid bid) {
        jpa.save(BidJpaMapper.toEntity(bid));
    }

    @Override
    public Optional<Bid> findById(BidId id) {
        return jpa.findById(id.value()).map(BidJpaMapper::toDomain);
    }
}
