package com.example.market.adapter.out.orderbook;

import com.example.market.adapter.out.persistence.jpa.mapper.BidJpaMapper;
import com.example.market.adapter.out.persistence.jpa.mapper.ListingJpaMapper;
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataBidRepository;
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataListingRepository;
import com.example.market.application.port.out.OrderBookQueryPort;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.trading.Bid;
import com.example.market.domain.trading.Listing;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JPA 기반 OrderBook 조회 어댑터.
 *
 * <p>{@link #acquireSkuLock} 는 PostgreSQL {@code pg_advisory_xact_lock} 으로 같은 SKU 의 매칭을
 * 트랜잭션 단위로 직렬화한다. 같은 SKU 동시 매칭에 의한 데드락 가능성을 코드 레벨이 아닌
 * DB 락으로 차단.</p>
 *
 * <p>H2 (dev) 는 advisory lock 미지원이라 {@code market.advisory-lock.enabled=false} 로 두고,
 * 락 없는 단순 조회로 동작한다 (단일 인스턴스라 race 없음). prod 는 true.</p>
 */
@Component
@Slf4j
public class JpaOrderBookQueryAdapter implements OrderBookQueryPort {

    @PersistenceContext
    private EntityManager em;

    private final SpringDataListingRepository listings;
    private final SpringDataBidRepository bids;
    private final boolean advisoryLockEnabled;

    public JpaOrderBookQueryAdapter(
            SpringDataListingRepository listings,
            SpringDataBidRepository bids,
            @Value("${market.advisory-lock.enabled:false}") boolean advisoryLockEnabled) {
        this.listings = listings;
        this.bids = bids;
        this.advisoryLockEnabled = advisoryLockEnabled;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquireSkuLock(SkuId skuId) {
        if (!advisoryLockEnabled) {
            log.trace("advisory lock disabled — skip for sku {}", skuId);
            return;
        }
        // PostgreSQL native — UUID 의 64-bit hash 로 lock key 생성
        long lockKey = skuId.value().getMostSignificantBits() ^ skuId.value().getLeastSignificantBits();
        em.createNativeQuery("SELECT pg_advisory_xact_lock(:k)")
                .setParameter("k", lockKey)
                .getSingleResult();
        log.trace("acquired SKU advisory lock sku={} key={}", skuId, lockKey);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Bid> findHighestBidForUpdate(SkuId skuId, Instant now) {
        // dev (H2, advisory-lock=false) 에서는 단일 인스턴스라 락 불필요 + H2 가 FOR UPDATE 와
        // 다른 트랜잭션의 commit 된 행을 다르게 처리해 매칭이 깨질 수 있음 → 단순 read 사용
        if (!advisoryLockEnabled) {
            return bids.topNActive(skuId.value(), now, PageRequest.of(0, 1))
                    .stream().findFirst().map(BidJpaMapper::toDomain);
        }
        return bids.findHighestActiveForUpdate(skuId.value(), now).map(BidJpaMapper::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Listing> findLowestAskForUpdate(SkuId skuId, Instant now) {
        if (!advisoryLockEnabled) {
            return listings.topNActive(skuId.value(), now, PageRequest.of(0, 1))
                    .stream().findFirst().map(ListingJpaMapper::toDomain);
        }
        return listings.findLowestActiveForUpdate(skuId.value(), now).map(ListingJpaMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Listing> topNAsks(SkuId skuId, int limit, Instant now) {
        return listings.topNActive(skuId.value(), now, PageRequest.of(0, limit))
                .stream().map(ListingJpaMapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Bid> topNBids(SkuId skuId, int limit, Instant now) {
        return bids.topNActive(skuId.value(), now, PageRequest.of(0, limit))
                .stream().map(BidJpaMapper::toDomain).toList();
    }
}
