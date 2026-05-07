package com.example.market.application.service;

import com.example.market.application.port.in.OhlcAggregationUseCase;
import com.example.market.application.port.out.OhlcCandleRepository;
import com.example.market.application.port.out.PriceTickRepository;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.marketdata.OhlcCandle;
import com.example.market.domain.marketdata.OhlcPeriod;
import com.example.market.domain.marketdata.PriceTick;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 한 (period × bucket) 의 raw PriceTick 들 → OHLC candle row 들로 집계.
 *
 * <p><b>흐름</b>:
 * <ol>
 *   <li>{@code [bucketStart, bucketEnd)} 에 거래가 있던 SKU 목록 조회</li>
 *   <li>SKU 별로 그 bucket 의 PriceTick 들 로드 → {@link OhlcCandle#from} 으로 1 candle</li>
 *   <li>{@code ohlcCandles.save(...)} — UNIQUE constraint 가 중복 INSERT 막음 (배치 재실행 안전)</li>
 * </ol>
 *
 * <p><b>왜 bucket 이 이미 닫혀야 하나</b>: 진행 중 bucket 을 집계하면 batch 후에 들어온 tick 이
 * 그 candle 에 누락 (수정 안 됨 — append-only). 호출자 (scheduler) 가 *직전 bucket* 만 넘겨야 안전.
 *
 * <p><b>왜 SKU 별로 따로 트랜잭션 안 만드나</b>: 한 batch (한 bucket) 에 거래된 SKU 가 보통 적음
 * (수십개 미만). 한 트랜잭션 안에 다 처리하는 게 단순 + 빠름. 트래픽 늘어 SKU 수가 수천이 되면
 * SKU 별로 saveAll batch + chunk 로 분리.</p>
 *
 * <p><b>중복 INSERT 처리</b>: 같은 (sku, period, bucket) 가 이미 있으면 unique constraint
 * → DataIntegrityViolationException. 보통 scheduler 가 이미 한 번 돌았다는 뜻이라 *조용히
 * skip + log warn*. 한 SKU 의 실패가 다른 SKU 처리를 막지 않음.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OhlcAggregationService implements OhlcAggregationUseCase {

    private final PriceTickRepository ticks;
    private final OhlcCandleRepository candles;

    /** 한 SKU 의 한 bucket 안 tick 수가 이걸 넘으면 chunk 로 잘라 처리 (메모리 폭증 방지). */
    private static final int MAX_TICKS_PER_BUCKET = 10_000;

    @Override
    @Transactional
    public int aggregateBucket(OhlcPeriod period, Instant bucketStart) {
        Instant bucketEnd = bucketStart.plus(period.duration());

        List<SkuId> activeSkus = ticks.findDistinctSkuIdsInRange(bucketStart, bucketEnd);
        if (activeSkus.isEmpty()) {
            log.debug("ohlc {} bucket={} — no trades, no candles", period, bucketStart);
            return 0;
        }

        int written = 0;
        for (SkuId skuId : activeSkus) {
            try {
                List<PriceTick> bucketTicks = ticks.findBySkuInRange(
                        skuId, bucketStart, bucketEnd, MAX_TICKS_PER_BUCKET);
                if (bucketTicks.isEmpty()) continue;   // SKU 가 마지막 순간 회수 (희박)
                OhlcCandle candle = OhlcCandle.from(skuId, period, bucketStart, bucketTicks);
                candles.save(candle);
                written++;
            } catch (DataIntegrityViolationException dup) {
                // UNIQUE 위반 — 이전 batch 가 이미 처리. 정상 idempotency.
                log.debug("ohlc duplicate (already aggregated) sku={} period={} bucket={}",
                        skuId, period, bucketStart);
            } catch (RuntimeException ex) {
                // 한 SKU 실패가 다른 SKU 막지 않게
                log.warn("ohlc aggregate failed sku={} period={} bucket={}: {}",
                        skuId, period, bucketStart, ex.getMessage());
            }
        }
        log.info("ohlc {} bucket={} skus={} candlesWritten={}",
                period, bucketStart, activeSkus.size(), written);
        return written;
    }

    /**
     * Scheduler 가 자주 호출하는 헬퍼 — *직전* bucket 1개를 닫는다.
     * 예: 매 분 실행하는 scheduler 가 {@code closePreviousBucket(ONE_MIN, now)} 호출 →
     *     "1분 전" bucket 이 집계됨.
     */
    public int closePreviousBucket(OhlcPeriod period, Instant now) {
        Instant currentBucket = period.bucketStart(now);
        Instant previousBucket = currentBucket.minus(Duration.ofMillis(period.duration().toMillis()));
        return aggregateBucket(period, previousBucket);
    }
}
