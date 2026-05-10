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
 * JPA 기반 호가창(OrderBook) 조회 어댑터.
 *
 * <p>{@link #acquireSkuLock} 는 PostgreSQL 의 {@code pg_advisory_xact_lock} (트랜잭션 단위로
 * 임의의 정수 키에 거는 응용 락) 으로 같은 SKU 의 매칭을 한 줄로 줄세운다. 같은 SKU 가 동시
 * 매칭될 때 발생할 수 있는 데드락을, 잠금 순서를 코드로 관리하지 않고 DB 락의 키로 통일해서
 * 구조적으로 차단한다.</p>
 *
 * <p>H2 (dev) 는 advisory lock 을 지원하지 않으므로 {@code market.advisory-lock.enabled=false}
 * 로 끄고, 락 없는 단순 조회로 동작한다 (개발 환경은 인스턴스 한 대라 동시 매칭 경쟁이 사실상
 * 없음). 운영(prod)은 true.</p>
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
        // PostgreSQL 의 native 함수 호출 — SKU UUID 를 64bit 정수로 압축 (XOR) 해서 lock key 로 사용.
        // 같은 SKU 면 항상 같은 키 → 동일 키 잠금만 발생해서 데드락이 구조적으로 불가능.
        long lockKey = skuId.value().getMostSignificantBits() ^ skuId.value().getLeastSignificantBits();
        em.createNativeQuery("SELECT pg_advisory_xact_lock(:k)")
                .setParameter("k", lockKey)
                .getSingleResult();
        log.trace("acquired SKU advisory lock sku={} key={}", skuId, lockKey);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Bid> findHighestBidForUpdate(SkuId skuId, Instant now) {
        // dev (H2, advisory-lock=false) 환경은 인스턴스가 한 대라 잠금이 굳이 필요 없다. 게다가
        // H2 는 PostgreSQL 의 FOR UPDATE 와 미세하게 다르게 동작해서 (다른 트랜잭션이 커밋한 행을
        // 처리하는 방식이 달라) 매칭이 어긋나는 사례가 있어서, dev 에서는 잠금 없는 단순 조회를 쓴다.
        if (!advisoryLockEnabled) {
            return bids.topNActive(skuId.value(), now, PageRequest.of(0, 1))
                    .stream().findFirst().map(BidJpaMapper::toDomain);
        }
        // FOR UPDATE 경로도 LIMIT 1 (Pageable) 로 강제 — JPQL 에 LIMIT 키워드가 없는데 이전엔
        // Optional 반환만 두었어서 호가 2개 이상일 때 NonUniqueResultException 으로 매칭 차단됐다.
        return bids.findHighestActiveForUpdate(skuId.value(), now, PageRequest.of(0, 1))
                .stream().findFirst().map(BidJpaMapper::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Listing> findLowestAskForUpdate(SkuId skuId, Instant now) {
        if (!advisoryLockEnabled) {
            return listings.topNActive(skuId.value(), now, PageRequest.of(0, 1))
                    .stream().findFirst().map(ListingJpaMapper::toDomain);
        }
        return listings.findLowestActiveForUpdate(skuId.value(), now, PageRequest.of(0, 1))
                .stream().findFirst().map(ListingJpaMapper::toDomain);
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
