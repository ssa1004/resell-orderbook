package com.example.market.adapter.out.persistence.jpa;

import com.example.market.adapter.out.persistence.jpa.mapper.BidJpaMapper;
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataBidRepository;
import com.example.market.application.port.out.StaleBidScanner;
import com.example.market.domain.trading.Bid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JpaStaleBidScanner implements StaleBidScanner {

    private final SpringDataBidRepository jpa;

    @Override
    @Transactional(readOnly = true)
    public List<Bid> findStaleActive(Instant cutoff, int batchSize) {
        return jpa.findStaleActive(cutoff, PageRequest.of(0, batchSize))
                .stream().map(BidJpaMapper::toDomain).toList();
    }
}
