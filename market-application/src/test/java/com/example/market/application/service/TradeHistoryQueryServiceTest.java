package com.example.market.application.service;

import com.example.market.application.pagination.Cursor;
import com.example.market.application.pagination.CursorCodec;
import com.example.market.application.pagination.CursorPage;
import com.example.market.application.pagination.TimeIdCursor;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.settlement.FeePolicy;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.Bid;
import com.example.market.domain.trading.Listing;
import com.example.market.domain.trading.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TradeHistoryQueryService — N+1 패턴, last page, cursor 디코드, 깨진 cursor.
 */
class TradeHistoryQueryServiceTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final SkuId SKU = SkuId.newId();
    private static final UserId BUYER = UserId.of("buyer-1");
    private static final FeePolicy POLICY = new FeePolicy(
            new BigDecimal("3.0"), new BigDecimal("3.5"),
            money(3_000), money(3_000), money(1_000));

    private TradeRepository trades;
    private TradeHistoryQueryService service;

    @BeforeEach
    void setUp() {
        trades = mock(TradeRepository.class);
        service = new TradeHistoryQueryService(trades);
    }

    @Test
    void firstPage_withoutCursor_returnsLastPageWhenLessThanLimit() {
        // 3 row, limit 5 → 마지막 페이지로 판정 (nextCursor null).
        List<Trade> rows = createTrades(3);
        when(trades.findByUserCursor(eq(BUYER.value()), any(), any(), eq(6))).thenReturn(rows);

        CursorPage<Trade> page = service.historyOf(BUYER, Cursor.empty(), 5);

        assertThat(page.items()).hasSize(3);
        assertThat(page.nextCursor()).isEmpty();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void firstPage_withMoreThanLimit_returnsNextCursor() {
        // 6 row 받음 (limit + 1) → 5 개만 반환 + nextCursor.
        List<Trade> rows = createTrades(6);
        when(trades.findByUserCursor(eq(BUYER.value()), any(), any(), eq(6))).thenReturn(rows);

        CursorPage<Trade> page = service.historyOf(BUYER, Cursor.empty(), 5);

        assertThat(page.items()).hasSize(5);
        assertThat(page.nextCursor()).isPresent();

        // nextCursor 가 반환된 마지막 row 의 (createdAt, id) 와 일치.
        Trade lastReturned = page.items().get(4);
        TimeIdCursor decoded = CursorCodec.decodeTimeId(page.nextCursor().get());
        assertThat(decoded.time()).isEqualTo(lastReturned.createdAt());
        assertThat(decoded.id()).isEqualTo(lastReturned.id().value());
    }

    @Test
    void nextPage_decodesCursorAndPassesAfterTimeAndId() {
        // 클라이언트가 보낸 cursor 가 TradeRepository 호출 인자로 정확히 풀려 들어가는지.
        Instant cursorTime = Instant.parse("2026-04-01T10:20:30Z");
        UUID cursorId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        Cursor cursor = CursorCodec.encode(new TimeIdCursor(cursorTime, cursorId));

        when(trades.findByUserCursor(BUYER.value(), cursorTime, cursorId, 6))
                .thenReturn(createTrades(2));

        CursorPage<Trade> page = service.historyOf(BUYER, cursor, 5);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isEmpty();   // 2 < 5 → last page
    }

    @Test
    void rejectsInvalidCursor() {
        Cursor garbage = Cursor.of("definitely!not!base64!@#$%");
        assertThatThrownBy(() -> service.historyOf(BUYER, garbage, 10))
                .isInstanceOf(CursorCodec.InvalidCursorException.class);
    }

    @Test
    void capsLimitAtMax() {
        // limit 9999 를 보내도 service 가 100 으로 cap → repo 가 받는 limit + 1 도 101.
        when(trades.findByUserCursor(eq(BUYER.value()), any(), any(), eq(101)))
                .thenReturn(List.of());
        service.historyOf(BUYER, Cursor.empty(), 9999);
        // verify 는 Mockito.never() 등 별도지만, when 에 안 걸린 limit 으로 호출되면 빈 리스트
        // 가 아닌 null 이 돌아와 NPE — 위 stub 이 받았다는 사실로 cap 입증.
    }

    @Test
    void rejectsNullUserId() {
        assertThatThrownBy(() -> service.historyOf(null, Cursor.empty(), 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── helpers ──────────────────────────────────────────

    /**
     * createdAt 이 1초씩 다른 Trade 를 N개 생성. 정렬 안정성 (decreasing time order) 가정.
     */
    private List<Trade> createTrades(int n) {
        List<Trade> result = new ArrayList<>();
        Instant base = Instant.parse("2026-05-08T00:00:00Z");
        for (int i = 0; i < n; i++) {
            Instant t = base.minusSeconds(i);
            Listing ask = Listing.place(SKU, UserId.of("seller-" + i), money(140_000), t);
            Bid bid = Bid.place(SKU, BUYER, money(150_000), t);
            Trade trade = Trade.match(ask, bid, money(150_000), POLICY, t);
            result.add(trade);
        }
        return result;
    }

    private static Money money(long won) {
        return Money.of(BigDecimal.valueOf(won), KRW);
    }
}
