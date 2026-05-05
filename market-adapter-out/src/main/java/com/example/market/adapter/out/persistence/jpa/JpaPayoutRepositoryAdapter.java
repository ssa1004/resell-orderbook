package com.example.market.adapter.out.persistence.jpa;

import com.example.market.adapter.out.persistence.jpa.mapper.PayoutJpaMapper;
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataPayoutRepository;
import com.example.market.application.port.out.PayoutRepository;
import com.example.market.domain.settlement.Payout;
import com.example.market.domain.settlement.PayoutId;
import com.example.market.domain.trading.TradeId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaPayoutRepositoryAdapter implements PayoutRepository {

    private final SpringDataPayoutRepository jpa;

    @Override
    public void save(Payout payout) {
        jpa.save(PayoutJpaMapper.toEntity(payout));
    }

    @Override
    public Optional<Payout> findById(PayoutId id) {
        return jpa.findById(id.value()).map(PayoutJpaMapper::toDomain);
    }

    @Override
    public Optional<Payout> findByTradeId(TradeId tradeId) {
        return jpa.findByTradeId(tradeId.value()).map(PayoutJpaMapper::toDomain);
    }
}
