package com.example.market.application.dlq

import java.time.Duration
import java.time.Instant

/**
 * 통계 조회 query — `GET /api/v1/admin/dlq/stats?from=&to=&bucket=PT1H` 매핑.
 *
 * bucket 단위가 너무 짧으면 (예: 1초) 응답 행이 폭발하고, 너무 길면 (예: 1일) 통계가 거칠어
 * 실시간 운영 가치가 떨어진다. PT1M ~ PT1D 범위로 강제.
 */
@JvmRecord
data class DlqStatsQuery(
    val from: Instant,
    val to: Instant,
    val bucket: Duration,
    val source: DlqSource?,
    val topSku: Int,
    val topErrorType: Int,
) {
    init {
        require(!from.isAfter(to)) { "from must be <= to" }
        require(!bucket.isNegative && !bucket.isZero) { "bucket must be positive" }
        require(bucket >= MIN_BUCKET) { "bucket must be >= $MIN_BUCKET" }
        require(bucket <= MAX_BUCKET) { "bucket must be <= $MAX_BUCKET" }
        require(topSku in 0..MAX_TOP_N) { "topSku must be in [0, $MAX_TOP_N]" }
        require(topErrorType in 0..MAX_TOP_N) { "topErrorType must be in [0, $MAX_TOP_N]" }
        // window 가 너무 길어 bucket 이 너무 많이 만들어지지 않도록 강제. 예: bucket=PT1M /
        // window=30d 면 43200 행 → OOM 위험.
        val bucketCount = Duration.between(from, to).toMillis() / bucket.toMillis()
        require(bucketCount <= MAX_BUCKETS) { "too many buckets: $bucketCount (max $MAX_BUCKETS)" }
    }

    companion object {
        @JvmField val MIN_BUCKET: Duration = Duration.ofMinutes(1)
        @JvmField val MAX_BUCKET: Duration = Duration.ofDays(1)
        const val MAX_BUCKETS: Int = 2000
        const val MAX_TOP_N: Int = 50
        const val DEFAULT_TOP_N: Int = 10
    }
}
