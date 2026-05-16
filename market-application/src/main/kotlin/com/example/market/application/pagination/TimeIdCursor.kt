package com.example.market.application.pagination

import java.time.Instant
import java.util.UUID

/**
 * Trade / Listing 처럼 (생성 시각, UUID) 복합 키로 정렬되는 자료의 cursor payload.
 *
 * 복합 키인 이유 — 같은 millisecond 에 생성된 두 row 가 있을 수 있어 시간만으로는 결정성을
 * 보장 못 한다. UUID 까지 같이 두면 (time, id) DESC 로 strict 단조 정렬이 되어 다음 페이지의
 * 경계가 명확해진다.
 *
 * SQL 패턴 (역순 — 최신 → 과거):
 * ```
 *   WHERE (created_at, id) < (?, ?)
 *   ORDER BY created_at DESC, id DESC
 *   LIMIT N + 1
 * ```
 *
 * `N+1` 행을 가져와 `N+1` 번째가 있으면 다음 페이지가 있다고 판단하고, 그것을 자르고
 * N 개만 반환한다. 마지막 페이지는 `N` 행 이하 — nextCursor 는 null.
 *
 * @param time UUID 와 함께 strict 정렬 키를 형성하는 timestamp (보통 created_at)
 * @param id   같은 timestamp 안에서의 tie-breaker
 */
@JvmRecord
data class TimeIdCursor(val time: Instant, val id: UUID)
