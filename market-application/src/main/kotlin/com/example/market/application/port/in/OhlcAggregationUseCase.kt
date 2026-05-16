package com.example.market.application.port.`in`

import com.example.market.domain.marketdata.OhlcPeriod
import java.time.Instant

/**
 * OHLC 집계 batch — 한 period 의 한 bucket 을 *닫고* candle row 들을 INSERT.
 *
 * **호출자**: scheduler (매 분/시간/일). `bucketStart` 는 *이미 닫힌 (시간 지난)* bucket 의
 * 시작 시각이어야 한다 (현재 진행 중 bucket 을 집계하면 늦게 들어온 tick 누락).
 *
 * @return 이번 호출에 INSERT 된 candle 수 (= 그 bucket 에 거래가 있던 SKU 수)
 */
interface OhlcAggregationUseCase {

    fun aggregateBucket(period: OhlcPeriod, bucketStart: Instant): Int
}
