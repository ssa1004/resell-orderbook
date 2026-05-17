package com.example.market.application.service;

import com.example.market.application.dlq.DlqAction;
import com.example.market.application.dlq.DlqActionResult;
import com.example.market.application.dlq.DlqAdminRateLimitedException;
import com.example.market.application.dlq.DlqMessageNotFoundException;
import com.example.market.application.dlq.DlqQuery;
import com.example.market.application.dlq.DlqSource;
import com.example.market.application.dlq.DlqStats;
import com.example.market.application.dlq.DlqStatsQuery;
import com.example.market.application.pagination.Cursor;
import com.example.market.application.port.out.AuditLogPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DlqAdminService} — 단건 DLQ 조회 / replay / discard / stats 거동.
 *
 * <p>핵심 검증:</p>
 * <ul>
 *   <li>discard 는 사유 (reason) 강제 — 없으면 IllegalArgumentException</li>
 *   <li>replay / discard 후 AuditLog 에 tradeId / skuId 포함된 entry 가 기록</li>
 *   <li>rate limit 거부 시 DlqAdminRateLimitedException (retryAfter 포함)</li>
 *   <li>없는 messageId 는 404 매핑용 NotFoundException</li>
 *   <li>stats 가 source / sku 별 집계를 정확히 채워 반환 (market 특유 bySku 차원)</li>
 * </ul>
 */
class DlqAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-15T12:00:00Z");

    private InMemoryDlqMessageStore store;
    private DlqTestSupport.AlwaysAllowRateLimiter rateLimiter;
    private DlqTestSupport.CapturingAuditLog audit;
    private DlqAdminService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryDlqMessageStore();
        rateLimiter = new DlqTestSupport.AlwaysAllowRateLimiter();
        audit = new DlqTestSupport.CapturingAuditLog();
        service = new DlqAdminService(store, rateLimiter, audit, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void list_filtersBySourceAndSku() {
        store.save(DlqTestSupport.makeMessage("m1", DlqSource.REFUND,
                "market.inspectionfailed", "PgFailureException", NOW, "trade-1", "sku-A"));
        store.save(DlqTestSupport.makeMessage("m2", DlqSource.MATCHING,
                "market.tradematched", "PgFailureException", NOW, "trade-2", "sku-A"));
        store.save(DlqTestSupport.makeMessage("m3", DlqSource.REFUND,
                "market.inspectionfailed", "PgFailureException", NOW, "trade-3", "sku-B"));

        var page = service.list(new DlqQuery(DlqSource.REFUND, null, null, null, null,
                "sku-A", Cursor.empty(), 50));

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).messageId()).isEqualTo("m1");
    }

    @Test
    void replay_recordsAuditEntryWithTradeAndSku() {
        store.save(DlqTestSupport.makeMessage("m1", DlqSource.REFUND,
                "market.inspectionfailed", "PgFailureException", NOW, "trade-1", "sku-A"));

        DlqActionResult result = service.perform("m1", DlqAction.REPLAY, "ops-alice", null);

        assertThat(result.action()).isEqualTo(DlqAction.REPLAY);
        assertThat(result.tradeId()).isEqualTo("trade-1");
        assertThat(result.skuId()).isEqualTo("sku-A");
        assertThat(store.replayed()).containsExactly("m1");

        assertThat(audit.entries).hasSize(1);
        AuditLogPort.AuditEntry entry = audit.entries.get(0);
        assertThat(entry.action()).isEqualTo(AuditLogPort.AuditAction.DLQ_REPLAY);
        assertThat(entry.actor()).isEqualTo("ops-alice");
        assertThat(entry.tradeId()).isEqualTo("trade-1");
        assertThat(entry.skuId()).isEqualTo("sku-A");
        assertThat(entry.outcome()).isEqualTo("OK");
        assertThat(entry.meta()).containsEntry("source", "REFUND");
    }

    @Test
    void discard_requiresReason() {
        store.save(DlqTestSupport.makeMessage("m1", DlqSource.REFUND,
                "market.inspectionfailed", "PgFailureException", NOW, "trade-1", "sku-A"));

        assertThatThrownBy(() -> service.perform("m1", DlqAction.DISCARD, "ops-bob", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        // 사전 검증 단계 — store 에 변경 없음.
        assertThat(store.discarded()).isEmpty();
    }

    @Test
    void discard_softDeletesAndRecordsAuditWithReason() {
        store.save(DlqTestSupport.makeMessage("m1", DlqSource.REFUND,
                "market.inspectionfailed", "PgFailureException", NOW, "trade-1", "sku-A"));

        service.perform("m1", DlqAction.DISCARD, "ops-bob", "obsolete after PG migration");

        assertThat(store.discarded()).containsExactly("m1");
        assertThat(audit.entries).hasSize(1);
        AuditLogPort.AuditEntry entry = audit.entries.get(0);
        assertThat(entry.action()).isEqualTo(AuditLogPort.AuditAction.DLQ_DISCARD);
        assertThat(entry.reason()).isEqualTo("obsolete after PG migration");
        assertThat(entry.outcome()).isEqualTo("SOFT_DELETED");
    }

    @Test
    void rateLimited_throwsWithRetryAfter() {
        DlqAdminService limited = new DlqAdminService(store,
                new DlqTestSupport.FirstNRejectRateLimiter(1), audit,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> limited.list(new DlqQuery(null, null, null, null, null, null,
                Cursor.empty(), 50)))
                .isInstanceOf(DlqAdminRateLimitedException.class)
                .satisfies(e -> assertThat(((DlqAdminRateLimitedException) e).getRetryAfterSeconds())
                        .isEqualTo(7L));
    }

    @Test
    void detail_notFound_throws404() {
        assertThatThrownBy(() -> service.detail("missing"))
                .isInstanceOf(DlqMessageNotFoundException.class);
    }

    @Test
    void stats_includesBySkuRanking() {
        // 같은 SKU 의 거래가 한꺼번에 stuck 되는 market 특유 패턴 — bySku 차원이 잡아야 함.
        store.save(DlqTestSupport.makeMessage("m1", DlqSource.REFUND,
                "market.inspectionfailed", "PgFailureException", NOW.minus(Duration.ofMinutes(10)),
                "trade-1", "sku-popular"));
        store.save(DlqTestSupport.makeMessage("m2", DlqSource.REFUND,
                "market.inspectionfailed", "PgFailureException", NOW.minus(Duration.ofMinutes(20)),
                "trade-2", "sku-popular"));
        store.save(DlqTestSupport.makeMessage("m3", DlqSource.MATCHING,
                "market.tradematched", "PgFailureException", NOW.minus(Duration.ofMinutes(5)),
                "trade-3", "sku-other"));

        DlqStats stats = service.stats(new DlqStatsQuery(
                NOW.minus(Duration.ofHours(1)), NOW, Duration.ofMinutes(15),
                null, 10, 10));

        assertThat(stats.total()).isEqualTo(3L);
        assertThat(stats.bySku()).extracting("skuId", "count")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("sku-popular", 2L),
                        org.assertj.core.groups.Tuple.tuple("sku-other", 1L));
        assertThat(stats.bySource()).containsEntry(DlqSource.REFUND, 2L);
        assertThat(stats.bySource()).containsEntry(DlqSource.MATCHING, 1L);
        // 15분 bucket * 4 = 1시간 윈도우 → 4개 행 반환
        assertThat(stats.buckets()).hasSize(4);
    }
}
