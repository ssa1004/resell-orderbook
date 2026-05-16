package com.example.market.application.service

import com.example.market.application.pagination.Cursor
import com.example.market.application.pagination.CursorCodec
import com.example.market.application.pagination.CursorPage
import com.example.market.application.pagination.TimeIdCursor
import com.example.market.application.port.`in`.TradeHistoryQueryUseCase
import com.example.market.application.port.out.TradeRepository
import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.Trade
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Trade 내역 cursor pagination read service (ADR-0025).
 *
 * ### N+1 패턴
 *
 * `limit` 만큼만 가져오면 *다음 페이지가 있는지* 알기 위해 별도 count 가 필요. 그래서
 * 한 번에 `limit + 1` 개를 요청해서 — `limit + 1` 번째가 있으면 다음 페이지 있다고
 * 판단 (그 row 는 결과에서 잘라냄). count query 1번 절약.
 */
@Service
@Transactional(readOnly = true)
open class TradeHistoryQueryService(
    private val trades: TradeRepository,
) : TradeHistoryQueryUseCase {

    override fun historyOf(userId: UserId?, after: Cursor, limit: Int): CursorPage<Trade> {
        // Java 호출자가 null 을 보낼 수 있다 — 의도된 IAE 로 신호 (NPE 가 아니라).
        if (userId == null) throw IllegalArgumentException("userId required")
        val safeLimit = limit.coerceIn(1, MAX_LIMIT)

        var afterTime: Instant? = null
        var afterId: UUID? = null
        if (!after.isEmpty()) {
            val decoded: TimeIdCursor? = CursorCodec.decodeTimeId(after)  // 깨진 cursor 면 IllegalArgumentException
            afterTime = decoded?.time
            afterId = decoded?.id
        }

        // limit + 1 — 다음 페이지가 있는지 한 row 더 가져와 본다.
        val raw: List<Trade> = trades.findByUserCursor(userId.value, afterTime, afterId, safeLimit + 1)

        if (raw.size <= safeLimit) {
            // 마지막 페이지 — nextCursor 없음.
            return CursorPage.last(raw)
        }

        // limit + 1 번째가 있다 → limit 개만 잘라내고 마지막 반환된 row 의 (createdAt, id) 로
        // 다음 cursor 생성. 직전이 아니라 반환된 마지막인 이유 — 클라이언트는 그 다음 row 부터
        // 받기를 기대.
        val page = raw.subList(0, safeLimit)
        val boundary = page[safeLimit - 1]
        val next = CursorCodec.encode(TimeIdCursor(boundary.createdAt, boundary.id.value))
        return CursorPage.of(page, next)
    }

    companion object {
        /** 한 페이지 최대 크기 — 컨트롤러에서도 검증하지만 service 레이어에서 한 번 더 cap. */
        const val MAX_LIMIT: Int = 100
    }
}
