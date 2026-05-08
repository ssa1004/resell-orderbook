/**
 * Cursor-based pagination 추상 (ADR-0025).
 *
 * <p>{@code OFFSET / LIMIT} 페이지네이션이 데이터가 늘면 *뒤 페이지로 갈수록 느려지는* 문제를
 * 해결한다 — DB 가 OFFSET 만큼 row 를 *전부 스캔하고 버린* 뒤 LIMIT 를 잡기 때문. cursor 는
 * 마지막으로 본 row 의 정렬 키를 받아 {@code WHERE sort_key < ?} 로 *직접 점프* 해 일정한
 * latency.</p>
 *
 * <p>구성:</p>
 *
 * <ul>
 *   <li>{@link com.example.market.application.pagination.Cursor} — 외부 노출 opaque token</li>
 *   <li>{@link com.example.market.application.pagination.CursorPage} — 페이지 결과 (items + nextCursor)</li>
 *   <li>{@link com.example.market.application.pagination.CursorCodec} — payload &lt;-&gt; token 변환</li>
 *   <li>{@link com.example.market.application.pagination.TimeIdCursor} — (Instant, UUID) 복합 cursor payload</li>
 * </ul>
 */
@org.springframework.modulith.NamedInterface("pagination")
package com.example.market.application.pagination;
