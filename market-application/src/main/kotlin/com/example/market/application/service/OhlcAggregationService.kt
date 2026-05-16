package com.example.market.application.service

import com.example.market.application.port.`in`.OhlcAggregationUseCase
import com.example.market.application.port.out.OhlcCandleRepository
import com.example.market.application.port.out.PriceTickRepository
import com.example.market.domain.marketdata.OhlcCandle
import com.example.market.domain.marketdata.OhlcPeriod
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * 한 (기간(period), 버킷(bucket)) 의 raw PriceTick (개별 체결 시세 단건) 들을 OHLC candle
 * (Open/High/Low/Close = 시작가/최고가/최저가/종가를 묶은 한 봉) row 로 사전 집계한다.
 *
 * **흐름**:
 * 1. `[bucketStart, bucketEnd)` 시간 구간에 거래가 있던 SKU 목록 조회
 * 2. SKU 별로 그 버킷의 PriceTick 들을 로드 → [OhlcCandle.from] 으로 candle 1개 생성
 * 3. `ohlcCandles.save(...)` — DB 의 UNIQUE 제약이 중복 INSERT 를 막아줘서 배치를
 *    다시 실행해도 안전 (멱등)
 *
 * **왜 버킷이 이미 닫혀 있어야 하나**: 진행 중인 버킷을 집계하면 배치 이후에 들어온 tick
 * 이 그 candle 에 누락된다 (이미 INSERT 한 candle 은 수정 안 됨 — append-only). 호출자 (스케줄러)
 * 는 직전 버킷만 넘겨야 안전.
 *
 * **왜 SKU 별로 트랜잭션을 따로 묶나**: 한 SKU 의 INSERT 가 UNIQUE 제약 위반으로 실패하면
 * 그 트랜잭션만 rollback 되고 다른 SKU 처리는 그대로 진행된다. 한 트랜잭션으로 묶어 두면
 * Hibernate flush 시 한 SKU 의 위반이 트랜잭션 전체를 rollback-only 로 만들어버려 같은 배치의
 * 나머지 SKU 들이 모두 `UnexpectedRollbackException` 으로 실패한다.
 *
 * **중복 INSERT 처리**: 같은 (sku, period, bucket) 이 이미 있으면 UNIQUE 제약 위반 →
 * DataIntegrityViolationException 발생. 보통 스케줄러가 한 번 더 돌았다는 뜻이라 (인스턴스가
 * 여러 대 + 약간의 시간차 등) 조용히 건너뛰고 log warn. 한 SKU 의 실패가 다른 SKU 처리를
 * 막지 않는다.
 */
@Service
open class OhlcAggregationService(
    private val ticks: PriceTickRepository,
    private val candles: OhlcCandleRepository,
    transactionManager: PlatformTransactionManager,
) : OhlcAggregationUseCase {

    // 각 SKU INSERT 를 짧은 트랜잭션 한 개로 — 한 SKU 실패가 다른 SKU 에 번지지 않음.
    private val perSkuTx: TransactionTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    override fun aggregateBucket(period: OhlcPeriod, bucketStart: Instant): Int {
        val bucketEnd = bucketStart.plus(period.duration())

        val activeSkus = ticks.findDistinctSkuIdsInRange(bucketStart, bucketEnd)
        if (activeSkus.isEmpty()) {
            log.debug("ohlc {} bucket={} — no trades, no candles", period, bucketStart)
            return 0
        }

        var written = 0
        for (skuId in activeSkus) {
            try {
                val saved: Boolean = perSkuTx.execute { _ ->
                    val bucketTicks = ticks.findBySkuInRange(
                        skuId, bucketStart, bucketEnd, MAX_TICKS_PER_BUCKET,
                    )
                    if (bucketTicks.isEmpty()) return@execute false
                    val candle = OhlcCandle.from(skuId, period, bucketStart, bucketTicks)
                    candles.save(candle)
                    true
                } ?: false
                if (saved) written++
            } catch (dup: DataIntegrityViolationException) {
                // UNIQUE 위반 — 이전 배치가 이미 처리한 버킷. 멱등 동작이라 정상 흐름.
                log.debug(
                    "ohlc duplicate (already aggregated) sku={} period={} bucket={}",
                    skuId, period, bucketStart,
                )
            } catch (ex: RuntimeException) {
                // 한 SKU 의 실패가 다른 SKU 의 처리를 막지 않게 격리
                log.warn(
                    "ohlc aggregate failed sku={} period={} bucket={}: {}",
                    skuId, period, bucketStart, ex.message,
                )
            }
        }
        log.info(
            "ohlc {} bucket={} skus={} candlesWritten={}",
            period, bucketStart, activeSkus.size, written,
        )
        return written
    }

    /**
     * 스케줄러가 자주 호출하는 헬퍼 — 직전 버킷 1개만 집계해서 닫는다.
     * 예: 매 분 실행되는 스케줄러가 `closePreviousBucket(ONE_MIN, now)` 호출 → "1분 전"
     * 버킷이 집계된다.
     */
    open fun closePreviousBucket(period: OhlcPeriod, now: Instant): Int {
        val currentBucket = period.bucketStart(now)
        val previousBucket = currentBucket.minus(Duration.ofMillis(period.duration().toMillis()))
        return aggregateBucket(period, previousBucket)
    }

    companion object {
        private val log = LoggerFactory.getLogger(OhlcAggregationService::class.java)

        /** 한 SKU 의 한 버킷 안 tick 수가 이 값을 넘으면 청크로 잘라 처리 (메모리 폭증 방지). */
        private const val MAX_TICKS_PER_BUCKET: Int = 10_000
    }
}
