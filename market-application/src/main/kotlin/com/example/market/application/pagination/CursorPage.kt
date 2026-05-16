package com.example.market.application.pagination

import java.util.Optional

/**
 * Cursor pagination 의 한 페이지 결과. 다음 페이지가 있으면 [nextCursor] 가 채워지고,
 * 마지막 페이지면 [nextCursor] 는 null (= empty).
 *
 * 총 개수 (`totalElements`) 는 *제공하지 않는다*. cursor pagination 의 본질이 *전체
 * 페이지 수를 모른 채 다음 페이지로만 진행* 이라 — 총 개수가 필요하면 별도 count endpoint
 * 또는 통계 cache 가 적합 (count 자체가 큰 테이블에선 비싼 query).
 *
 * @param T 페이지 안의 원소 타입
 */
class CursorPage<T>(items: List<T>, private val nextCursorOrNull: Cursor?) {

    @get:JvmName("items")
    val items: List<T> = java.util.List.copyOf(items)

    fun nextCursor(): Optional<Cursor> = Optional.ofNullable(nextCursorOrNull)

    fun hasNext(): Boolean = nextCursorOrNull != null && !nextCursorOrNull.isEmpty()

    companion object {
        @JvmStatic
        fun <T> last(items: List<T>): CursorPage<T> = CursorPage(items, null)

        @JvmStatic
        fun <T> of(items: List<T>, nextCursor: Cursor?): CursorPage<T> = CursorPage(items, nextCursor)
    }
}
