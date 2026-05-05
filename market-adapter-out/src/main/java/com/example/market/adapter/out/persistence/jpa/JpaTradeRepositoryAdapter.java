package com.example.market.adapter.out.persistence.jpa;

import com.example.market.adapter.out.persistence.jpa.mapper.TradeJpaMapper;
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataTradeRepository;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.trading.Trade;
import com.example.market.domain.trading.TradeId;
import com.example.market.domain.trading.TradeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaTradeRepositoryAdapter implements TradeRepository {

    private final SpringDataTradeRepository jpa;

    @Override
    public void save(Trade trade) {
        jpa.save(TradeJpaMapper.toEntity(trade));
    }

    @Override
    public Optional<Trade> findById(TradeId id) {
        return jpa.findById(id.value()).map(TradeJpaMapper::toDomain);
    }

    @Override
    public List<Trade> findStaleCreated(Instant cutoff) {
        return jpa.findStaleCreated(cutoff, PageRequest.of(0, 200))
                .stream().map(TradeJpaMapper::toDomain).toList();
    }

    @Override
    public List<Trade> findByStatus(TradeStatus status, int limit) {
        return jpa.findByStatus(status, PageRequest.of(0, limit))
                .stream().map(TradeJpaMapper::toDomain).toList();
    }
}
