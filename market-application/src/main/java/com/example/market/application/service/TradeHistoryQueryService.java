package com.example.market.application.service;

import com.example.market.application.pagination.Cursor;
import com.example.market.application.pagination.CursorCodec;
import com.example.market.application.pagination.CursorPage;
import com.example.market.application.pagination.TimeIdCursor;
import com.example.market.application.port.in.TradeHistoryQueryUseCase;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.Trade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Trade 내역 cursor pagination read service (ADR-0025).
 *
 * <h3>N+1 패턴</h3>
 *
 * <p>{@code limit} 만큼만 가져오면 *다음 페이지가 있는지* 알기 위해 별도 count 가 필요. 그래서
 * 한 번에 {@code limit + 1} 개를 요청해서 — {@code limit + 1} 번째가 있으면 다음 페이지 있다고
 * 판단 (그 row 는 결과에서 잘라냄). count query 1번 절약.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TradeHistoryQueryService implements TradeHistoryQueryUseCase {

    /** 한 페이지 최대 크기 — 컨트롤러에서도 검증하지만 service 레이어에서 한 번 더 cap. */
    public static final int MAX_LIMIT = 100;

    private final TradeRepository trades;

    @Override
    public CursorPage<Trade> historyOf(UserId userId, Cursor after, int limit) {
        if (userId == null) throw new IllegalArgumentException("userId required");
        int safeLimit = Math.max(1, Math.min(MAX_LIMIT, limit));

        Instant afterTime = null;
        UUID afterId = null;
        if (after != null && !after.isEmpty()) {
            TimeIdCursor decoded = CursorCodec.decodeTimeId(after);  // 깨진 cursor 면 IllegalArgumentException
            afterTime = decoded.time();
            afterId = decoded.id();
        }

        // limit + 1 — 다음 페이지가 있는지 한 row 더 가져와 본다.
        List<Trade> raw = trades.findByUserCursor(userId.value(), afterTime, afterId, safeLimit + 1);

        if (raw.size() <= safeLimit) {
            // 마지막 페이지 — nextCursor 없음.
            return CursorPage.last(raw);
        }

        // limit + 1 번째가 있다 → limit 개만 잘라내고 마지막 *반환된* row 의 (createdAt, id) 로
        // 다음 cursor 생성. 직전이 아니라 *반환된 마지막* 인 이유 — 클라이언트는 그 다음 row 부터
        // 받기를 기대.
        List<Trade> page = raw.subList(0, safeLimit);
        Trade boundary = page.get(safeLimit - 1);
        Cursor next = CursorCodec.encode(new TimeIdCursor(boundary.createdAt(), boundary.id().value()));
        return CursorPage.of(page, next);
    }
}
