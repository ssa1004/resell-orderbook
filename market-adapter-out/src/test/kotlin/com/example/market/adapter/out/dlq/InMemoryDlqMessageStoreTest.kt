package com.example.market.adapter.out.dlq

import com.example.market.application.dlq.DlqMessage
import com.example.market.application.dlq.DlqMessageDetail
import com.example.market.application.dlq.DlqQuery
import com.example.market.application.dlq.DlqSource
import com.example.market.application.dlq.DlqStatsQuery
import com.example.market.application.pagination.Cursor
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * 어댑터 단위 — 필터링 / cursor / stats 의 핵심 거동.
 */
class InMemoryDlqMessageStoreTest {

    private val now: Instant = Instant.parse("2026-05-15T10:00:00Z")

    @Test
    fun `cursor pagination preserves order and produces nextCursor`() {
        val store = InMemoryDlqMessageStore()
        repeat(7) { i ->
            store.save(message("m-$i", now.minusSeconds(i.toLong()), DlqSource.REFUND, "sku-A"))
        }

        val q = DlqQuery(null, null, null, null, null, null, Cursor.empty(), 3)
        val first = store.list(q)
        assertEquals(3, first.items.size)
        // 최신 정렬 — m-0 (now) → m-1 (now-1s) → m-2.
        assertEquals(listOf("m-0", "m-1", "m-2"), first.items.map { it.messageId })
        assertTrue(first.hasNext())

        val second = store.list(q.copy(cursor = first.nextCursor().get()))
        assertEquals(listOf("m-3", "m-4", "m-5"), second.items.map { it.messageId })

        val third = store.list(q.copy(cursor = second.nextCursor().get()))
        assertEquals(listOf("m-6"), third.items.map { it.messageId })
        assertFalse(third.hasNext())
    }

    @Test
    fun `sku filter narrows to matching SKU only`() {
        val store = InMemoryDlqMessageStore()
        store.save(message("m-a", now, DlqSource.REFUND, "sku-A"))
        store.save(message("m-b", now, DlqSource.REFUND, "sku-B"))

        val page = store.list(DlqQuery(null, null, null, null, null, "sku-A", Cursor.empty(), 50))

        assertEquals(listOf("m-a"), page.items.map { it.messageId })
    }

    @Test
    fun `replay increments attemptCount and is recorded`() {
        val store = InMemoryDlqMessageStore()
        store.save(message("m-1", now, DlqSource.REFUND, "sku-A"))

        val outcome = store.replay("m-1", now.plusSeconds(1))

        assertTrue(outcome.success)
        assertEquals(2, outcome.newAttemptCount)
        val found = store.find("m-1")
        assertNotNull(found.orElse(null))
        assertEquals(2, found.get().summary.attemptCount)
    }

    @Test
    fun `stats bySku ranks SKUs by frequency`() {
        val store = InMemoryDlqMessageStore()
        // sku-popular 3건, sku-other 1건 — market 특유 ranking.
        store.save(message("m-1", now.minus(Duration.ofMinutes(5)), DlqSource.REFUND, "sku-popular"))
        store.save(message("m-2", now.minus(Duration.ofMinutes(10)), DlqSource.REFUND, "sku-popular"))
        store.save(message("m-3", now.minus(Duration.ofMinutes(15)), DlqSource.REFUND, "sku-popular"))
        store.save(message("m-4", now.minus(Duration.ofMinutes(20)), DlqSource.MATCHING, "sku-other"))

        val stats = store.stats(DlqStatsQuery(
            now.minus(Duration.ofHours(1)), now, Duration.ofMinutes(15), null, 10, 10,
        ))

        assertEquals(4L, stats.total)
        assertEquals("sku-popular", stats.bySku[0].skuId)
        assertEquals(3L, stats.bySku[0].count)
        assertEquals(1L, stats.bySku[1].count)
        // bucket 4개 (1시간 / 15분) — empty bucket 도 포함됨.
        assertEquals(4, stats.buckets.size)
    }

    private fun message(id: String, at: Instant, source: DlqSource, sku: String): DlqMessageDetail {
        val summary = DlqMessage(id, source, source.topicHint, "PgFailureException",
            "PG down", at, 1, "trade-$id", sku)
        return DlqMessageDetail(summary, "{}", "stack-$id", emptyMap(), 0, 0L, at, at)
    }
}
