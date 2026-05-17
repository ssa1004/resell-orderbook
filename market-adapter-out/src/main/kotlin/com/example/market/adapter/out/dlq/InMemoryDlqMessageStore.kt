package com.example.market.adapter.out.dlq

import com.example.market.application.dlq.DlqErrorTypeCount
import com.example.market.application.dlq.DlqMessage
import com.example.market.application.dlq.DlqMessageDetail
import com.example.market.application.dlq.DlqQuery
import com.example.market.application.dlq.DlqSkuCount
import com.example.market.application.dlq.DlqSource
import com.example.market.application.dlq.DlqStats
import com.example.market.application.dlq.DlqStatsBucket
import com.example.market.application.dlq.DlqStatsQuery
import com.example.market.application.pagination.Cursor
import com.example.market.application.pagination.CursorPage
import com.example.market.application.port.out.DlqMessageStore
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.EnumMap
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 단일 인스턴스용 in-memory DLQ store — dev 및 Kafka 가 꺼진 환경에서 사용. prod 는
 * [KafkaDlqMessageStore] 가 같은 인터페이스를 Kafka `-dlt` 토픽과 묶어 제공.
 *
 * `@ConditionalOnMissingBean(DlqMessageStore::class)` 로 prod 의 Kafka 어댑터가 활성화되면
 * 자동으로 비활성. dev 의 `market.kafka.bootstrap-servers=""` 환경에서 fallback.
 *
 * 본 store 는 인스턴스 재시작 시 데이터가 lost — dev 운영 가이드에 명시 (운영자가 실제로
 * 잡힌 DLQ 메시지를 보려면 Kafka 가 켜진 stage / prod 환경 필요).
 */
@Component
@ConditionalOnProperty(
    name = ["market.dlq.store.kafka.enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class InMemoryDlqMessageStore : DlqMessageStore {

    private val log = LoggerFactory.getLogger(javaClass)
    private val rows = ConcurrentHashMap<String, DlqMessageDetail>()

    init {
        log.info("DLQ store: in-memory (dev / single-pod). prod 는 KafkaDlqMessageStore 로 대체.")
    }

    override fun save(message: DlqMessageDetail) {
        rows[message.summary.messageId] = message
    }

    override fun list(query: DlqQuery): CursorPage<DlqMessage> {
        val filtered = filter(query)
        val skip = decodeSkip(query.cursor)
        val from = skip.coerceAtMost(filtered.size)
        val to = (from + query.size).coerceAtMost(filtered.size)
        val page = filtered.subList(from, to)
        return if (to < filtered.size) CursorPage.of(page, encodeSkip(to)) else CursorPage.last(page)
    }

    override fun find(messageId: String): Optional<DlqMessageDetail> =
        Optional.ofNullable(rows[messageId])

    override fun countAndSample(filter: DlqQuery, sampleSize: Int): DlqMessageStore.CountSample {
        val matched = filter(filter)
        val sample = matched.subList(0, sampleSize.coerceAtMost(matched.size))
        return DlqMessageStore.CountSample(matched.size.toLong(), sample)
    }

    override fun matchingIds(filter: DlqQuery): List<String> =
        filter(filter).map { it.messageId }

    override fun replay(messageId: String, now: Instant): DlqMessageStore.ReplayOutcome {
        val row = rows[messageId]
            ?: return DlqMessageStore.ReplayOutcome(false, 0, "not found")
        val newAttempt = row.summary.attemptCount + 1
        val updated = row.copy(
            summary = row.summary.copy(attemptCount = newAttempt),
            lastSeenAt = now,
        )
        rows[messageId] = updated
        // in-memory 어댑터는 실제 Kafka 재발행을 못 한다 — 운영 시뮬레이션 용도.
        log.info("[dev DLQ] replay simulated messageId={} attempt={}", messageId, newAttempt)
        return DlqMessageStore.ReplayOutcome(true, newAttempt, null)
    }

    override fun discard(messageId: String, now: Instant) {
        // soft delete — in-memory 라 그냥 제거하지만, "DISCARDED tombstone" 의 의미로 별 set 에
        // 보관하면 stats 의 'discarded' 카운터를 노출할 수 있음. 단순화 위해 그냥 제거.
        val removed = rows.remove(messageId)
        if (removed != null) {
            log.info("[dev DLQ] discard simulated messageId={}", messageId)
        }
    }

    override fun stats(query: DlqStatsQuery): DlqStats {
        val within = rows.values.filter {
            val at = it.summary.occurredAt
            !at.isBefore(query.from) && at.isBefore(query.to)
                && (query.source == null || it.summary.source == query.source)
        }
        val total = within.size.toLong()

        val bySource = EnumMap<DlqSource, Long>(DlqSource::class.java)
        within.forEach { bySource.merge(it.summary.source, 1L, Long::plus) }

        val bucketMs = query.bucket.toMillis()
        val buckets = mutableListOf<DlqStatsBucket>()
        var start = query.from
        while (start.isBefore(query.to)) {
            val end = start.plusMillis(bucketMs)
            val inBucket = within.filter { !it.summary.occurredAt.isBefore(start) && it.summary.occurredAt.isBefore(end) }
            val byBucketSource = EnumMap<DlqSource, Long>(DlqSource::class.java)
            inBucket.forEach { byBucketSource.merge(it.summary.source, 1L, Long::plus) }
            buckets += DlqStatsBucket(start, inBucket.size.toLong(), byBucketSource)
            start = end
        }

        val byErrorType = within.groupingBy { it.summary.errorType }.eachCount()
            .map { DlqErrorTypeCount(it.key, it.value.toLong()) }
            .sortedByDescending { it.count }
            .take(query.topErrorType)

        val bySku = within.mapNotNull { it.summary.skuId }
            .groupingBy { it }.eachCount()
            .map { DlqSkuCount(it.key, it.value.toLong()) }
            .sortedByDescending { it.count }
            .take(query.topSku)

        return DlqStats(query.from, query.to, query.bucket, total, buckets, bySource, byErrorType, bySku)
    }

    override fun purgeBefore(threshold: Instant): Int {
        var n = 0
        val it = rows.values.iterator()
        while (it.hasNext()) {
            if (it.next().summary.occurredAt.isBefore(threshold)) {
                it.remove(); n++
            }
        }
        return n
    }

    // ── 내부 helper ────────────────────────────────────────

    private fun filter(query: DlqQuery): List<DlqMessage> =
        rows.values
            .map { it.summary }
            .filter { m -> query.source?.let { m.source == it } ?: true }
            .filter { m -> query.topic?.let { m.topic.startsWith(it) } ?: true }
            .filter { m -> query.errorType?.let { m.errorType == it } ?: true }
            .filter { m -> query.from?.let { !m.occurredAt.isBefore(it) } ?: true }
            .filter { m -> query.to?.let { m.occurredAt.isBefore(it) } ?: true }
            .filter { m -> query.skuId?.let { m.skuId == it } ?: true }
            .sortedByDescending { it.occurredAt }

    private fun decodeSkip(cursor: Cursor): Int {
        if (cursor.isEmpty()) return 0
        return try {
            val raw = String(Base64.getUrlDecoder().decode(cursor.token), StandardCharsets.UTF_8)
            require(raw.startsWith(SKIP_PREFIX))
            raw.substring(SKIP_PREFIX.length).toInt()
        } catch (e: IllegalArgumentException) {
            0
        }
    }

    private fun encodeSkip(skip: Int): Cursor {
        val raw = (SKIP_PREFIX + skip).toByteArray(StandardCharsets.UTF_8)
        return Cursor.of(Base64.getUrlEncoder().withoutPadding().encodeToString(raw))
    }

    companion object {
        private const val SKIP_PREFIX = "dlq-skip:"
    }
}
