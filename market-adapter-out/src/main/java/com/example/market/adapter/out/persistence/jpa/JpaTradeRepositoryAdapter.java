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
import java.util.UUID;

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
    public List<Trade> findStaleCreated(Instant cutoff, int limit) {
        // limit 을 그대로 전달 — 호출자의 batch 처리 단위와 fetch 단위를 일치시킨다.
        return jpa.findStaleCreated(cutoff, PageRequest.of(0, limit))
                .stream().map(TradeJpaMapper::toDomain).toList();
    }

    @Override
    public List<Trade> findByStatus(TradeStatus status, int limit) {
        return jpa.findByStatus(status, PageRequest.of(0, limit))
                .stream().map(TradeJpaMapper::toDomain).toList();
    }

    @Override
    public List<Trade> findByUserCursor(String userId, Instant afterTime, UUID afterId, int limit) {
        // 첫 페이지 (cursor 없음) 와 그 다음 페이지 (cursor 있음) 의 query 가 다름 — JPA 의 동적
        // 비교를 OR 분기 대신 메서드 분리 (인덱스 plan 도 더 단순).
        PageRequest page = PageRequest.of(0, limit);
        if (afterTime == null || afterId == null) {
            return jpa.findByUserFirstPage(userId, page)
                    .stream().map(TradeJpaMapper::toDomain).toList();
        }
        return jpa.findByUserAfter(userId, afterTime, afterId, page)
                .stream().map(TradeJpaMapper::toDomain).toList();
    }
}
