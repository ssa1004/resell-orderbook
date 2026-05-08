package com.example.market.adapter.out.persistence.jpa;

import com.example.market.adapter.out.persistence.jpa.mapper.PriceTickJpaMapper;
import com.example.market.adapter.out.persistence.jpa.repository.SkuLookup;
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataPriceTickRepository;
import com.example.market.application.port.out.PriceTickRepository;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.marketdata.PriceTick;
import com.example.market.domain.shared.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

/**
 * Adapter — domain {@link PriceTick} 와 JPA entity 사이의 변환.
 *
 * <p>{@link #aggregate} 는 SKU 의 통화를 알아야 Money 로 감쌀 수 있다. 통화는 *최근 1 tick* 에서
 * 추론 — 한 SKU 가 통화를 바꾸는 일은 정의상 없음 (한정판 sneaker 가격이 KRW 였다 USD 가 되진 않음).
 * count == 0 이면 통화도 모르므로 KRW default. application 측이 count 체크 후 무시 가능.</p>
 */
@Repository
@RequiredArgsConstructor
public class JpaPriceTickRepositoryAdapter implements PriceTickRepository {

    private static final Currency DEFAULT_CURRENCY = Currency.getInstance("KRW");

    private final SpringDataPriceTickRepository jpa;

    @Override
    public void save(PriceTick tick) {
        jpa.save(PriceTickJpaMapper.toEntity(tick));
    }

    @Override
    public List<PriceTick> findBySkuInRange(SkuId skuId, Instant from, Instant to, int limit) {
        return jpa.findBySkuIdAndOccurredAtBetweenOrderByOccurredAtDesc(
                        skuId.value(), from, to, PageRequest.of(0, limit))
                .stream()
                .map(PriceTickJpaMapper::toDomain)
                .toList();
    }

    @Override
    public List<PriceTick> findBySkuAfter(SkuId skuId, long afterId, int limit) {
        return jpa.findBySkuIdAndIdGreaterThanOrderByIdAsc(
                        skuId.value(), afterId, PageRequest.of(0, limit))
                .stream()
                .map(PriceTickJpaMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<PriceTick> findLatest(SkuId skuId) {
        // Snowflake id DESC = 시간 DESC. occurred_at 컬럼 한 번 더 정렬할 필요 없음 (ADR-0018).
        return jpa.findFirstBySkuIdOrderByIdDesc(skuId.value())
                .map(PriceTickJpaMapper::toDomain);
    }

    @Override
    public PriceAggregation aggregate(SkuId skuId, Instant from, Instant to) {
        var raw = jpa.aggregate(skuId.value(), from, to);
        if (raw == null || raw.count() == 0) {
            return new PriceAggregation(0L, null, null, null);
        }
        // 통화는 최근 tick 1건에서 가져옴. 통상 SKU 당 통화 고정.
        Currency currency = jpa.findFirstBySkuIdOrderByIdDesc(skuId.value())
                .map(SkuLookup::currencyOf)
                .orElse(DEFAULT_CURRENCY);
        BigDecimal avgBd = raw.avg() == null
                ? null
                : BigDecimal.valueOf(raw.avg()).setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP);
        return new PriceAggregation(
                raw.count(),
                Money.of(raw.min(), currency),
                avgBd == null ? null : Money.of(avgBd, currency),
                Money.of(raw.max(), currency)
        );
    }

    @Override
    public List<SkuId> findDistinctSkuIdsInRange(Instant from, Instant to) {
        return jpa.findDistinctSkuIdsInRange(from, to).stream()
                .map(uuid -> SkuId.of(uuid.toString()))
                .toList();
    }
}
