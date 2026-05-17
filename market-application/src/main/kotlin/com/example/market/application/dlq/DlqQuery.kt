package com.example.market.application.dlq

import com.example.market.application.pagination.Cursor
import java.time.Instant

/**
 * DLQ 목록 조회 query 파라미터 묶음. 모든 필드는 optional 필터.
 *
 * @param source     출처 enum 으로 좁히기 — null 이면 전체
 * @param topic      원래 토픽 이름 prefix 매칭 — null 이면 전체
 * @param errorType  예외 클래스 simple name 매칭 — null 이면 전체
 * @param from       `occurredAt >= from` (UTC) — null 이면 하한 없음
 * @param to         `occurredAt <  to`  (UTC) — null 이면 상한 없음
 * @param skuId      market 특유 — 특정 SKU 의 메시지만. null 이면 전체
 * @param cursor     다음 페이지 cursor — 첫 페이지면 [Cursor.empty]
 * @param size       한 페이지 크기 — 1 ~ 200 사이 (어댑터에서 enforce)
 */
@JvmRecord
data class DlqQuery(
    val source: DlqSource?,
    val topic: String?,
    val errorType: String?,
    val from: Instant?,
    val to: Instant?,
    val skuId: String?,
    val cursor: Cursor,
    val size: Int,
) {
    init {
        require(size in MIN_SIZE..MAX_SIZE) { "size must be in [$MIN_SIZE, $MAX_SIZE], was $size" }
        if (from != null && to != null) {
            require(!from.isAfter(to)) { "from must be <= to" }
        }
    }

    companion object {
        const val MIN_SIZE: Int = 1
        const val MAX_SIZE: Int = 200
        const val DEFAULT_SIZE: Int = 50
    }
}
