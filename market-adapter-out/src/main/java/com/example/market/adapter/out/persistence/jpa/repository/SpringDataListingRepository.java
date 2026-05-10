package com.example.market.adapter.out.persistence.jpa.repository;

import com.example.market.adapter.out.persistence.jpa.entity.ListingJpaEntity;
import com.example.market.domain.trading.ListingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.QueryHint;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringDataListingRepository extends JpaRepository<ListingJpaEntity, UUID> {

    /**
     * Lowest ASK 1건 — FOR UPDATE SKIP LOCKED. JPQL 에는 LIMIT 키워드가 없으므로 Pageable 로
     * LIMIT 1 을 강제한다. 같은 SKU 의 ACTIVE Listing 이 2개 이상일 때 단일 결과형 쿼리는 Spring
     * Data JPA 가 NonUniqueResultException 으로 던져 매칭 자체가 차단된다 — caller 가
     * {@code stream().findFirst()} 로 첫 행을 받는다.
     * Hibernate 의 jakarta.persistence.lock.timeout = -2 → SKIP LOCKED.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("""
            SELECT l FROM ListingJpaEntity l
            WHERE l.skuId = :skuId
              AND l.status = com.example.market.domain.trading.ListingStatus.ACTIVE
              AND l.expiresAt > :now
            ORDER BY l.askPrice ASC, l.createdAt ASC
            """)
    List<ListingJpaEntity> findLowestActiveForUpdate(
            @Param("skuId") UUID skuId, @Param("now") Instant now, Pageable pageable);

    @Query("""
            SELECT l FROM ListingJpaEntity l
            WHERE l.skuId = :skuId
              AND l.status = com.example.market.domain.trading.ListingStatus.ACTIVE
              AND l.expiresAt > :now
            ORDER BY l.askPrice ASC, l.createdAt ASC
            """)
    List<ListingJpaEntity> topNActive(
            @Param("skuId") UUID skuId, @Param("now") Instant now, Pageable pageable);

    @Query("""
            SELECT l FROM ListingJpaEntity l
            WHERE l.status = com.example.market.domain.trading.ListingStatus.ACTIVE
              AND l.expiresAt < :now
            ORDER BY l.expiresAt ASC
            """)
    List<ListingJpaEntity> findStaleActive(@Param("now") Instant now, Pageable pageable);

    List<ListingJpaEntity> findByStatus(ListingStatus status, Pageable pageable);
}
