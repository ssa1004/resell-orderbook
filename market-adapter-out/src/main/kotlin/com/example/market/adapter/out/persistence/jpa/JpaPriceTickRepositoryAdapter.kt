package com.example.market.adapter.out.persistence.jpa

import com.example.market.adapter.out.persistence.jpa.mapper.PriceTickJpaMapper
import com.example.market.adapter.out.persistence.jpa.repository.SkuLookup
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataPriceTickRepository
import com.example.market.application.port.out.PriceTickRepository
import com.example.market.application.port.out.PriceTickRepository.PriceAggregation
import com.example.market.domain.catalog.SkuId
import com.example.market.domain.marketdata.PriceTick
import com.example.market.domain.shared.Money
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.Currency
import java.util.Optional

/**
 * Adapter — domain [PriceTick] 와 JPA entity 사이의 변환.
 *
 * [aggregate] 는 SKU 의 통화를 알아야 Money 로 감쌀 수 있다. 통화는 *최근 1 tick* 에서
 * 추론 — 한 SKU 가 통화를 바꾸는 일은 정의상 없음 (한정판 sneaker 가격이 KRW 였다 USD 가 되진 않음).
 * count == 0 이면 통화도 모르므로 KRW default. application 측이 count 체크 후 무시 가능.
 */
@Repository
class JpaPriceTickRepositoryAdapter(
    private val jpa: SpringDataPriceTickRepository,
) : PriceTickRepository {

    override fun save(tick: PriceTick) {
        jpa.save(PriceTickJpaMapper.toEntity(tick))
    }

    override fun findBySkuInRange(
        skuId: SkuId,
        from: Instant,
        to: Instant,
        limit: Int,
    ): List<PriceTick> =
        jpa.findBySkuIdAndOccurredAtBetweenOrderByOccurredAtDesc(
            skuId.value, from, to, PageRequest.of(0, limit),
        ).map(PriceTickJpaMapper::toDomain)

    override fun findBySkuAfter(skuId: SkuId, afterId: Long, limit: Int): List<PriceTick> =
        jpa.findBySkuIdAndIdGreaterThanOrderByIdAsc(
            skuId.value, afterId, PageRequest.of(0, limit),
        ).map(PriceTickJpaMapper::toDomain)

    override fun findLatest(skuId: SkuId): Optional<PriceTick> {
        // Snowflake id DESC = 시간 DESC. occurred_at 컬럼 한 번 더 정렬할 필요 없음 (ADR-0018).
        return jpa.findFirstBySkuIdOrderByIdDesc(skuId.value)
            .map(PriceTickJpaMapper::toDomain)
    }

    override fun aggregate(skuId: SkuId, from: Instant, to: Instant): PriceAggregation {
        val raw = jpa.aggregate(skuId.value, from, to)
        if (raw.count == 0L) {
            return PriceAggregation(0L, null, null, null)
        }
        // 통화는 최근 tick 1건에서 가져옴. 통상 SKU 당 통화 고정.
        val currency: Currency = jpa.findFirstBySkuIdOrderByIdDesc(skuId.value)
            .map(SkuLookup::currencyOf)
            .orElse(DEFAULT_CURRENCY)
        val avgBd: BigDecimal? = raw.avg?.let {
            BigDecimal.valueOf(it).setScale(currency.defaultFractionDigits, RoundingMode.HALF_UP)
        }
        return PriceAggregation(
            raw.count,
            raw.min?.let { Money.of(it, currency) },
            avgBd?.let { Money.of(it, currency) },
            raw.max?.let { Money.of(it, currency) },
        )
    }

    override fun findDistinctSkuIdsInRange(from: Instant, to: Instant): List<SkuId> =
        jpa.findDistinctSkuIdsInRange(from, to).map { SkuId(it) }

    companion object {
        private val DEFAULT_CURRENCY: Currency = Currency.getInstance("KRW")
    }
}
