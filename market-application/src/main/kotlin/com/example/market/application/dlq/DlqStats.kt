package com.example.market.application.dlq

import java.time.Duration
import java.time.Instant

/**
 * DLQ 적재량의 시간/source/errorType/SKU 별 집계 결과.
 *
 * `bySku` 가 market 특유 차원 — 같은 SKU 의 거래가 한꺼번에 stuck 되는 상황 (특정 sneaker 의
 * PG 응답 지연 / 검수센터 정전 등) 을 빠르게 감지하기 위한 ranking. 같은 의도로 `byErrorType`
 * 은 외부 의존성의 장애 종류를 한눈에 보여준다.
 *
 * 시간 bucket 은 `from` 부터 `bucket` 단위로 등분된다 — bucket=PT1H, from=00:00, to=03:00
 * 이면 [00:00–01:00, 01:00–02:00, 02:00–03:00) 의 3개 bucket 이 반환된다.
 */
@JvmRecord
data class DlqStats(
    val from: Instant,
    val to: Instant,
    val bucket: Duration,
    val total: Long,
    val buckets: List<DlqStatsBucket>,
    val bySource: Map<DlqSource, Long>,
    val byErrorType: List<DlqErrorTypeCount>,
    val bySku: List<DlqSkuCount>,
)

/**
 * 시간 bucket 1구간의 source 별 적재량. `start <= occurredAt < start+bucket` 범위.
 */
@JvmRecord
data class DlqStatsBucket(val start: Instant, val count: Long, val bySource: Map<DlqSource, Long>)

/**
 * errorType (예외 클래스 simple name) 별 적재량 — 상위 N 개만 반환 (서비스에서 컷오프).
 */
@JvmRecord
data class DlqErrorTypeCount(val errorType: String, val count: Long)

/**
 * SKU 별 적재량 — 같은 sneaker 의 거래가 동시 stuck 인 패턴을 빠르게 식별. market 특유.
 */
@JvmRecord
data class DlqSkuCount(val skuId: String, val count: Long)
