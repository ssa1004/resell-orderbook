package com.example.market.application.port.`in`

import com.example.market.application.pagination.Cursor
import com.example.market.application.pagination.CursorPage
import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.Trade

/**
 * 한 사용자 (구매자 / 판매자) 의 거래 내역을 cursor pagination 으로 조회 (ADR-0025).
 *
 * OFFSET 페이지네이션 대신 cursor — *뒤 페이지에서도 일정한 latency*. 거래 내역은 누적되며
 * 클라이언트가 보통 처음 한두 페이지만 보면 끝나므로, 총 페이지 수가 필요 없는 cursor 방식이
 * 적합 (timeline 무한 스크롤 화면의 일반 패턴).
 */
interface TradeHistoryQueryUseCase {

    /**
     * @param userId 거래 내역을 볼 사용자 (구매 또는 판매). null 시 IllegalArgumentException (Java 호환).
     * @param after  이전 페이지의 nextCursor (null/empty 면 첫 페이지)
     * @param limit  한 페이지 크기 (1 ~ 100)
     */
    fun historyOf(userId: UserId?, after: Cursor, limit: Int): CursorPage<Trade>
}
