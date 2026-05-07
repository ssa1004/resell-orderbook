package com.example.market.adapter.out.persistence.jpa;

import com.example.market.adapter.out.persistence.jpa.mapper.OhlcCandleJpaMapper;
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataOhlcCandleRepository;
import com.example.market.application.port.out.OhlcCandleRepository;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.marketdata.OhlcCandle;
import com.example.market.domain.marketdata.OhlcPeriod;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class JpaOhlcCandleRepositoryAdapter implements OhlcCandleRepository {

    private final SpringDataOhlcCandleRepository jpa;

    @Override
    public void save(OhlcCandle candle) {
        jpa.save(OhlcCandleJpaMapper.toEntity(candle));
    }

    @Override
    public List<OhlcCandle> findBySkuInRange(SkuId skuId, OhlcPeriod period,
                                             Instant from, Instant to, int limit) {
        return jpa.findBySkuIdAndPeriodAndBucketStartBetweenOrderByBucketStartDesc(
                        skuId.value(), period, from, to, PageRequest.of(0, limit))
                .stream()
                .map(OhlcCandleJpaMapper::toDomain)
                .toList();
    }
}
