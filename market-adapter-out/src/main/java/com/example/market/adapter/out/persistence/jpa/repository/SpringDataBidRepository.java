package com.example.market.adapter.out.persistence.jpa.repository;

import com.example.market.adapter.out.persistence.jpa.entity.BidJpaEntity;
import com.example.market.domain.trading.BidStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringDataBidRepository extends JpaRepository<BidJpaEntity, UUID> {

    /**
     * Highest BID 1건 — FOR UPDATE SKIP LOCKED. JPQL 에는 LIMIT 키워드가 없으므로 Pageable 로
     * LIMIT 1 을 강제한다. 같은 SKU 의 ACTIVE BID 가 2개 이상일 때 단일 결과형 쿼리는 Spring
     * Data JPA 가 NonUniqueResultException 으로 던져 매칭 자체가 차단된다 — caller 가
     * {@code stream().findFirst()} 로 첫 행을 받는다.
     * Hibernate 의 jakarta.persistence.lock.timeout = -2 → SKIP LOCKED.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("""
            SELECT b FROM BidJpaEntity b
            WHERE b.skuId = :skuId
              AND b.status = com.example.market.domain.trading.BidStatus.ACTIVE
              AND b.expiresAt > :now
            ORDER BY b.bidPrice DESC, b.createdAt ASC
            """)
    List<BidJpaEntity> findHighestActiveForUpdate(
            @Param("skuId") UUID skuId, @Param("now") Instant now, Pageable pageable);

    @Query("""
            SELECT b FROM BidJpaEntity b
            WHERE b.skuId = :skuId
              AND b.status = com.example.market.domain.trading.BidStatus.ACTIVE
              AND b.expiresAt > :now
            ORDER BY b.bidPrice DESC, b.createdAt ASC
            """)
    List<BidJpaEntity> topNActive(
            @Param("skuId") UUID skuId, @Param("now") Instant now, Pageable pageable);

    @Query("""
            SELECT b FROM BidJpaEntity b
            WHERE b.status = com.example.market.domain.trading.BidStatus.ACTIVE
              AND b.expiresAt < :now
            ORDER BY b.expiresAt ASC
            """)
    List<BidJpaEntity> findStaleActive(@Param("now") Instant now, Pageable pageable);

    List<BidJpaEntity> findByStatus(BidStatus status, Pageable pageable);
}
