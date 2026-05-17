package com.example.market.adapter.out.persistence.jpa

import com.example.market.adapter.out.persistence.jpa.mapper.TradeJpaMapper
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataTradeRepository
import com.example.market.application.port.out.TradeRepository
import com.example.market.domain.trading.Trade
import com.example.market.domain.trading.TradeId
import com.example.market.domain.trading.TradeStatus
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.Optional
import java.util.UUID

@Repository
class JpaTradeRepositoryAdapter(
    private val jpa: SpringDataTradeRepository,
) : TradeRepository {

    override fun save(trade: Trade) {
        jpa.save(TradeJpaMapper.toEntity(trade))
    }

    override fun findById(id: TradeId): Optional<Trade> =
        jpa.findById(id.value).map(TradeJpaMapper::toDomain)

    override fun findStaleCreated(cutoff: Instant, limit: Int): List<Trade> {
        // limit 을 그대로 전달 — 호출자의 batch 처리 단위와 fetch 단위를 일치시킨다.
        return jpa.findStaleCreated(cutoff, PageRequest.of(0, limit))
            .map(TradeJpaMapper::toDomain)
    }

    override fun findByStatus(status: TradeStatus, limit: Int): List<Trade> =
        jpa.findByStatus(status, PageRequest.of(0, limit))
            .map(TradeJpaMapper::toDomain)

    override fun findByUserCursor(
        userId: String,
        afterTime: Instant?,
        afterId: UUID?,
        limit: Int,
    ): List<Trade> {
        // 첫 페이지 (cursor 없음) 와 그 다음 페이지 (cursor 있음) 의 query 가 다름 — JPA 의 동적
        // 비교를 OR 분기 대신 메서드 분리 (인덱스 plan 도 더 단순).
        val page = PageRequest.of(0, limit)
        return if (afterTime == null || afterId == null) {
            jpa.findByUserFirstPage(userId, page).map(TradeJpaMapper::toDomain)
        } else {
            jpa.findByUserAfter(userId, afterTime, afterId, page).map(TradeJpaMapper::toDomain)
        }
    }
}
