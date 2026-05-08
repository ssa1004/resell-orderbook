package com.example.market.application.pagination;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Cursor pagination 의 한 페이지 결과. 다음 페이지가 있으면 {@link #nextCursor} 가 채워지고,
 * 마지막 페이지면 {@link #nextCursor} 는 null (= empty).
 *
 * <p>총 개수 ({@code totalElements}) 는 *제공하지 않는다*. cursor pagination 의 본질이 *전체
 * 페이지 수를 모른 채 다음 페이지로만 진행* 이라 — 총 개수가 필요하면 별도 count endpoint
 * 또는 통계 cache 가 적합 (count 자체가 큰 테이블에선 비싼 query).</p>
 *
 * @param <T> 페이지 안의 원소 타입
 */
public final class CursorPage<T> {

    private final List<T> items;
    private final Cursor nextCursor;     // null → 마지막 페이지

    public CursorPage(List<T> items, Cursor nextCursor) {
        this.items = List.copyOf(Objects.requireNonNull(items, "items"));
        this.nextCursor = nextCursor;
    }

    public static <T> CursorPage<T> last(List<T> items) {
        return new CursorPage<>(items, null);
    }

    public static <T> CursorPage<T> of(List<T> items, Cursor nextCursor) {
        return new CursorPage<>(items, nextCursor);
    }

    public List<T> items() { return items; }

    public Optional<Cursor> nextCursor() { return Optional.ofNullable(nextCursor); }

    public boolean hasNext() { return nextCursor != null && !nextCursor.isEmpty(); }
}
