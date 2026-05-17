package com.example.market.application.service;

import com.example.market.application.dlq.DlqMessage;
import com.example.market.application.dlq.DlqMessageDetail;
import com.example.market.application.dlq.DlqQuery;
import com.example.market.application.dlq.DlqSource;
import com.example.market.application.dlq.DlqStats;
import com.example.market.application.dlq.DlqStatsBucket;
import com.example.market.application.dlq.DlqStatsQuery;
import com.example.market.application.dlq.DlqErrorTypeCount;
import com.example.market.application.dlq.DlqSkuCount;
import com.example.market.application.pagination.CursorPage;
import com.example.market.application.port.out.DlqMessageStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test 전용 in-memory DlqMessageStore — 운영용 KafkaDlqMessageStore 와 같은 인터페이스로 동작.
 *
 * <p>운영 어댑터는 Kafka `-dlt` 토픽을 listen + JPA 테이블에 적재하지만, 본 store 는 ConcurrentHashMap
 * 한 개로 압축. 테스트 가독성을 위해 filter / sort / pagination 은 단순 stream 처리.</p>
 */
class InMemoryDlqMessageStore implements DlqMessageStore {

    private final Map<String, DlqMessageDetail> rows = new ConcurrentHashMap<>();
    private final List<String> replayed = new ArrayList<>();
    private final List<String> discarded = new ArrayList<>();
    private final AtomicInteger purgedCount = new AtomicInteger();

    @Override
    public void save(DlqMessageDetail message) {
        rows.put(message.summary().messageId(), message);
    }

    @Override
    public CursorPage<DlqMessage> list(DlqQuery query) {
        List<DlqMessage> filtered = filter(query);
        // cursor 는 단순화 — opaque token = "skip:N" 으로 처리.
        int skip = 0;
        if (!query.cursor().isEmpty()) {
            String token = new String(java.util.Base64.getUrlDecoder().decode(query.cursor().token()));
            if (token.startsWith("skip:")) skip = Integer.parseInt(token.substring(5));
        }
        int from = Math.min(skip, filtered.size());
        int to = Math.min(from + query.size(), filtered.size());
        List<DlqMessage> page = filtered.subList(from, to);
        if (to < filtered.size()) {
            String nextToken = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(("skip:" + to).getBytes());
            return CursorPage.of(page, com.example.market.application.pagination.Cursor.of(nextToken));
        }
        return CursorPage.last(page);
    }

    @Override
    public Optional<DlqMessageDetail> find(String messageId) {
        return Optional.ofNullable(rows.get(messageId));
    }

    @Override
    public CountSample countAndSample(DlqQuery filter, int sampleSize) {
        List<DlqMessage> filtered = filter(filter);
        List<DlqMessage> sample = filtered.subList(0, Math.min(sampleSize, filtered.size()));
        return new CountSample(filtered.size(), sample);
    }

    @Override
    public List<String> matchingIds(DlqQuery filter) {
        return filter(filter).stream().map(DlqMessage::messageId).toList();
    }

    @Override
    public ReplayOutcome replay(String messageId, Instant now) {
        DlqMessageDetail row = rows.get(messageId);
        if (row == null) return new ReplayOutcome(false, 0, "not found");
        replayed.add(messageId);
        int newAttempts = row.summary().attemptCount() + 1;
        DlqMessage updated = new DlqMessage(
                row.summary().messageId(), row.summary().source(), row.summary().topic(),
                row.summary().errorType(), row.summary().errorMessage(), row.summary().occurredAt(),
                newAttempts, row.summary().tradeId(), row.summary().skuId());
        rows.put(messageId, new DlqMessageDetail(updated, row.payload(), row.stackTrace(),
                row.headers(), row.partition(), row.offset(), row.firstSeenAt(), now));
        return new ReplayOutcome(true, newAttempts, null);
    }

    @Override
    public void discard(String messageId, Instant now) {
        DlqMessageDetail row = rows.remove(messageId);
        if (row != null) discarded.add(messageId);
    }

    @Override
    public DlqStats stats(DlqStatsQuery query) {
        List<DlqMessageDetail> within = new ArrayList<>();
        for (DlqMessageDetail row : rows.values()) {
            Instant at = row.summary().occurredAt();
            if (!at.isBefore(query.from()) && at.isBefore(query.to())) {
                if (query.source() == null || row.summary().source() == query.source()) {
                    within.add(row);
                }
            }
        }
        long total = within.size();
        Map<DlqSource, Long> bySource = new EnumMap<>(DlqSource.class);
        for (DlqMessageDetail r : within) {
            bySource.merge(r.summary().source(), 1L, Long::sum);
        }

        // bucket
        List<DlqStatsBucket> buckets = new ArrayList<>();
        long bucketMs = query.bucket().toMillis();
        Instant start = query.from();
        while (start.isBefore(query.to())) {
            Instant end = start.plusMillis(bucketMs);
            Instant s = start;
            List<DlqMessageDetail> inBucket = within.stream()
                    .filter(r -> !r.summary().occurredAt().isBefore(s)
                            && r.summary().occurredAt().isBefore(end))
                    .toList();
            Map<DlqSource, Long> bucketBySource = new EnumMap<>(DlqSource.class);
            for (DlqMessageDetail r : inBucket) bucketBySource.merge(r.summary().source(), 1L, Long::sum);
            buckets.add(new DlqStatsBucket(start, inBucket.size(), bucketBySource));
            start = end;
        }

        // top errorType
        Map<String, Long> errorCounts = new HashMap<>();
        for (DlqMessageDetail r : within) errorCounts.merge(r.summary().errorType(), 1L, Long::sum);
        List<DlqErrorTypeCount> topErrors = errorCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(query.topErrorType())
                .map(e -> new DlqErrorTypeCount(e.getKey(), e.getValue()))
                .toList();

        // top sku
        Map<String, Long> skuCounts = new LinkedHashMap<>();
        for (DlqMessageDetail r : within) {
            if (r.summary().skuId() != null) skuCounts.merge(r.summary().skuId(), 1L, Long::sum);
        }
        List<DlqSkuCount> topSku = skuCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(query.topSku())
                .map(e -> new DlqSkuCount(e.getKey(), e.getValue()))
                .toList();

        return new DlqStats(query.from(), query.to(), query.bucket(), total,
                buckets, bySource, topErrors, topSku);
    }

    @Override
    public int purgeBefore(Instant threshold) {
        // test stub — 실 구현에선 DISCARDED row 만 hard delete.
        int n = 0;
        var it = rows.values().iterator();
        while (it.hasNext()) {
            var row = it.next();
            if (row.summary().occurredAt().isBefore(threshold)) {
                it.remove();
                n++;
            }
        }
        purgedCount.addAndGet(n);
        return n;
    }

    // ── 테스트 보조 ─────────────────────────────────────────

    List<String> replayed() { return List.copyOf(replayed); }
    List<String> discarded() { return List.copyOf(discarded); }
    int rowCount() { return rows.size(); }

    private List<DlqMessage> filter(DlqQuery query) {
        return rows.values().stream()
                .map(DlqMessageDetail::summary)
                .filter(m -> query.source() == null || m.source() == query.source())
                .filter(m -> query.topic() == null || m.topic().startsWith(query.topic()))
                .filter(m -> query.errorType() == null || m.errorType().equals(query.errorType()))
                .filter(m -> query.from() == null || !m.occurredAt().isBefore(query.from()))
                .filter(m -> query.to() == null || m.occurredAt().isBefore(query.to()))
                .filter(m -> query.skuId() == null || query.skuId().equals(m.skuId()))
                .sorted(Comparator.comparing(DlqMessage::occurredAt).reversed())
                .toList();
    }
}
