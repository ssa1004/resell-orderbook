package com.example.market.adapter.out.persistence.jpa;

import com.example.market.adapter.out.persistence.jpa.mapper.ListingJpaMapper;
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataListingRepository;
import com.example.market.application.port.out.ListingRepository;
import com.example.market.domain.trading.Listing;
import com.example.market.domain.trading.ListingId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaListingRepositoryAdapter implements ListingRepository {

    private final SpringDataListingRepository jpa;

    @Override
    public void save(Listing listing) {
        jpa.save(ListingJpaMapper.toEntity(listing));
    }

    @Override
    public Optional<Listing> findById(ListingId id) {
        return jpa.findById(id.value()).map(ListingJpaMapper::toDomain);
    }
}
