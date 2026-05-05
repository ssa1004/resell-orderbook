package com.example.market.adapter.out.persistence.jpa;

import com.example.market.adapter.out.persistence.jpa.mapper.RefundJpaMapper;
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataRefundRepository;
import com.example.market.application.port.out.RefundRepository;
import com.example.market.domain.settlement.Refund;
import com.example.market.domain.settlement.RefundId;
import com.example.market.domain.trading.TradeId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaRefundRepositoryAdapter implements RefundRepository {

    private final SpringDataRefundRepository jpa;

    @Override
    public void save(Refund refund) {
        jpa.save(RefundJpaMapper.toEntity(refund));
    }

    @Override
    public Optional<Refund> findById(RefundId id) {
        return jpa.findById(id.value()).map(RefundJpaMapper::toDomain);
    }

    @Override
    public Optional<Refund> findByTradeId(TradeId tradeId) {
        return jpa.findByTradeId(tradeId.value()).map(RefundJpaMapper::toDomain);
    }
}
