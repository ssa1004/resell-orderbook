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
 * 한 (기간(period), 버킷(bucket)) 의 raw PriceTick (개별 체결 시세 단건) 들을 OHLC candle
 * (Open/High/Low/Close = 시작가/최고가/최저가/종가를 묶은 한 봉) row 로 사전 집계한다.
 *
 * <p><b>흐름</b>:
 * <ol>
 *   <li>{@code [bucketStart, bucketEnd)} 시간 구간에 거래가 있던 SKU 목록 조회</li>
 *   <li>SKU 별로 그 버킷의 PriceTick 들을 로드 → {@link OhlcCandle#from} 으로 candle 1개 생성</li>
 *   <li>{@code ohlcCandles.save(...)} — DB 의 UNIQUE 제약이 중복 INSERT 를 막아줘서 배치를
 *       다시 실행해도 안전 (멱등)</li>
 * </ol>
 *
 * <p><b>왜 버킷이 이미 닫혀 있어야 하나</b>: 진행 중인 버킷을 집계하면 배치 이후에 들어온 tick
 * 이 그 candle 에 누락된다 (이미 INSERT 한 candle 은 수정 안 됨 — append-only). 호출자 (스케줄러)
 * 는 직전 버킷만 넘겨야 안전.
 *
 * <p><b>왜 SKU 별로 트랜잭션을 따로 만들지 않나</b>: 한 배치 (한 버킷) 에서 거래된 SKU 수가
 * 보통 적다 (수십 개 미만). 한 트랜잭션 안에서 다 처리하는 게 단순 + 빠름. 트래픽이 늘어
 * SKU 수가 수천이 되면 SKU 별로 chunk + saveAll 로 분리.</p>
 *
 * <p><b>중복 INSERT 처리</b>: 같은 (sku, period, bucket) 이 이미 있으면 UNIQUE 제약 위반 →
 * DataIntegrityViolationException 발생. 보통 스케줄러가 한 번 더 돌았다는 뜻이라 (인스턴스가
 * 여러 대 + 약간의 시간차 등) 조용히 건너뛰고 log warn. 한 SKU 의 실패가 다른 SKU 처리를
 * 막지 않는다.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OhlcAggregationService implements OhlcAggregationUseCase {

    private final PriceTickRepository ticks;
    private final OhlcCandleRepository candles;

    /** 한 SKU 의 한 버킷 안 tick 수가 이 값을 넘으면 청크로 잘라 처리 (메모리 폭증 방지). */
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
                if (bucketTicks.isEmpty()) continue;   // SKU 가 마지막 순간 거래 회수 (드문 경우)
                OhlcCandle candle = OhlcCandle.from(skuId, period, bucketStart, bucketTicks);
                candles.save(candle);
                written++;
            } catch (DataIntegrityViolationException dup) {
                // UNIQUE 위반 — 이전 배치가 이미 처리한 버킷. 멱등 동작이라 정상 흐름.
                log.debug("ohlc duplicate (already aggregated) sku={} period={} bucket={}",
                        skuId, period, bucketStart);
            } catch (RuntimeException ex) {
                // 한 SKU 의 실패가 다른 SKU 의 처리를 막지 않게 격리
                log.warn("ohlc aggregate failed sku={} period={} bucket={}: {}",
                        skuId, period, bucketStart, ex.getMessage());
            }
        }
        log.info("ohlc {} bucket={} skus={} candlesWritten={}",
                period, bucketStart, activeSkus.size(), written);
        return written;
    }

    /**
     * 스케줄러가 자주 호출하는 헬퍼 — 직전 버킷 1개만 집계해서 닫는다.
     * 예: 매 분 실행되는 스케줄러가 {@code closePreviousBucket(ONE_MIN, now)} 호출 → "1분 전"
     * 버킷이 집계된다.
     */
    public int closePreviousBucket(OhlcPeriod period, Instant now) {
        Instant currentBucket = period.bucketStart(now);
        Instant previousBucket = currentBucket.minus(Duration.ofMillis(period.duration().toMillis()));
        return aggregateBucket(period, previousBucket);
    }
}
