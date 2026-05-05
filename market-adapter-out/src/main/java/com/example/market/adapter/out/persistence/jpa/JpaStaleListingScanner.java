package com.example.market.adapter.out.persistence.jpa;

import com.example.market.adapter.out.persistence.jpa.mapper.ListingJpaMapper;
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataListingRepository;
import com.example.market.application.port.out.StaleListingScanner;
import com.example.market.domain.trading.Listing;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JpaStaleListingScanner implements StaleListingScanner {

    private final SpringDataListingRepository jpa;

    @Override
    @Transactional(readOnly = true)
    public List<Listing> findStaleActive(Instant cutoff, int batchSize) {
        return jpa.findStaleActive(cutoff, PageRequest.of(0, batchSize))
                .stream().map(ListingJpaMapper::toDomain).toList();
    }
}
