package com.example.market.adapter.out.orderbook

import com.example.market.adapter.out.persistence.jpa.mapper.BidJpaMapper
import com.example.market.adapter.out.persistence.jpa.mapper.ListingJpaMapper
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataBidRepository
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataListingRepository
import com.example.market.application.port.out.OrderBookQueryPort
import com.example.market.domain.catalog.SkuId
import com.example.market.domain.trading.Bid
import com.example.market.domain.trading.Listing
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.Optional

/**
 * JPA 기반 호가창(OrderBook) 조회 어댑터.
 *
 * [acquireSkuLock] 는 PostgreSQL 의 `pg_advisory_xact_lock` (트랜잭션 단위로
 * 임의의 정수 키에 거는 응용 락) 으로 같은 SKU 의 매칭을 한 줄로 줄세운다. 같은 SKU 가 동시
 * 매칭될 때 발생할 수 있는 데드락을, 잠금 순서를 코드로 관리하지 않고 DB 락의 키로 통일해서
 * 구조적으로 차단한다.
 *
 * H2 (dev) 는 advisory lock 을 지원하지 않으므로 `market.advisory-lock.enabled=false`
 * 로 끄고, 락 없는 단순 조회로 동작한다 (개발 환경은 인스턴스 한 대라 동시 매칭 경쟁이 사실상
 * 없음). 운영(prod)은 true.
 */
@Component
class JpaOrderBookQueryAdapter(
    private val listings: SpringDataListingRepository,
    private val bids: SpringDataBidRepository,
    @Value("\${market.advisory-lock.enabled:false}") private val advisoryLockEnabled: Boolean,
) : OrderBookQueryPort {

    private val log = LoggerFactory.getLogger(javaClass)

    @PersistenceContext
    private lateinit var em: EntityManager

    @Transactional(propagation = Propagation.MANDATORY)
    override fun acquireSkuLock(skuId: SkuId) {
        if (!advisoryLockEnabled) {
            log.trace("advisory lock disabled — skip for sku {}", skuId)
            return
        }
        // PostgreSQL 의 native 함수 호출 — SKU UUID 를 64bit 정수로 압축 (XOR) 해서 lock key 로 사용.
        // 같은 SKU 면 항상 같은 키 → 동일 키 잠금만 발생해서 데드락이 구조적으로 불가능.
        val lockKey = skuId.value.mostSignificantBits xor skuId.value.leastSignificantBits
        em.createNativeQuery("SELECT pg_advisory_xact_lock(:k)")
            .setParameter("k", lockKey)
            .singleResult
        log.trace("acquired SKU advisory lock sku={} key={}", skuId, lockKey)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun findHighestBidForUpdate(skuId: SkuId, now: Instant): Optional<Bid> {
        // dev (H2, advisory-lock=false) 환경은 인스턴스가 한 대라 잠금이 굳이 필요 없다. 게다가
        // H2 는 PostgreSQL 의 FOR UPDATE 와 미세하게 다르게 동작해서 (다른 트랜잭션이 커밋한 행을
        // 처리하는 방식이 달라) 매칭이 어긋나는 사례가 있어서, dev 에서는 잠금 없는 단순 조회를 쓴다.
        if (!advisoryLockEnabled) {
            return bids.topNActive(skuId.value, now, PageRequest.of(0, 1))
                .firstOrNull()?.let { Optional.of(BidJpaMapper.toDomain(it)) }
                ?: Optional.empty()
        }
        // FOR UPDATE 경로도 LIMIT 1 (Pageable) 로 강제 — JPQL 에 LIMIT 키워드가 없는데 이전엔
        // Optional 반환만 두었어서 호가 2개 이상일 때 NonUniqueResultException 으로 매칭 차단됐다.
        return bids.findHighestActiveForUpdate(skuId.value, now, PageRequest.of(0, 1))
            .firstOrNull()?.let { Optional.of(BidJpaMapper.toDomain(it)) }
            ?: Optional.empty()
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun findLowestAskForUpdate(skuId: SkuId, now: Instant): Optional<Listing> {
        if (!advisoryLockEnabled) {
            return listings.topNActive(skuId.value, now, PageRequest.of(0, 1))
                .firstOrNull()?.let { Optional.of(ListingJpaMapper.toDomain(it)) }
                ?: Optional.empty()
        }
        return listings.findLowestActiveForUpdate(skuId.value, now, PageRequest.of(0, 1))
            .firstOrNull()?.let { Optional.of(ListingJpaMapper.toDomain(it)) }
            ?: Optional.empty()
    }

    @Transactional(readOnly = true)
    override fun topNAsks(skuId: SkuId, limit: Int, now: Instant): List<Listing> =
        listings.topNActive(skuId.value, now, PageRequest.of(0, limit))
            .map(ListingJpaMapper::toDomain)

    @Transactional(readOnly = true)
    override fun topNBids(skuId: SkuId, limit: Int, now: Instant): List<Bid> =
        bids.topNActive(skuId.value, now, PageRequest.of(0, limit))
            .map(BidJpaMapper::toDomain)
}
