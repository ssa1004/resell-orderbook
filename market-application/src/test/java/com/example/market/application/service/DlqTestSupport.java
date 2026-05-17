package com.example.market.application.service;

import com.example.market.application.dlq.DlqMessage;
import com.example.market.application.dlq.DlqMessageDetail;
import com.example.market.application.dlq.DlqSource;
import com.example.market.application.port.out.AdminRateLimiter;
import com.example.market.application.port.out.AuditLogPort;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트용 공통 헬퍼 — DLQ fake / fixture builder.
 */
final class DlqTestSupport {

    private DlqTestSupport() {}

    static DlqMessageDetail makeMessage(String id, DlqSource source, String topic, String errorType,
                                        Instant occurredAt, String tradeId, String skuId) {
        DlqMessage summary = new DlqMessage(id, source, topic, errorType,
                errorType + ": failed", occurredAt, 1, tradeId, skuId);
        return new DlqMessageDetail(summary, "{\"tradeId\":\"" + tradeId + "\"}",
                "stack-trace-" + id, Map.of("X-Original-Topic", topic),
                0, 0L, occurredAt, occurredAt);
    }

    /** 항상 허용 — admin rate limiter 가 실제 거동에 끼어들지 않게. */
    static final class AlwaysAllowRateLimiter implements AdminRateLimiter {
        final List<String> calls = new ArrayList<>();

        @Override
        public Decision tryAcquire(String scope, String actorKey) {
            calls.add(scope + "|" + actorKey);
            return Decision.allow();
        }
    }

    /** 처음 N 번만 거부 — replay 시 rate limit 동작 검증. */
    static final class FirstNRejectRateLimiter implements AdminRateLimiter {
        private final int n;
        private final AtomicInteger calls = new AtomicInteger();

        FirstNRejectRateLimiter(int n) { this.n = n; }

        @Override
        public Decision tryAcquire(String scope, String actorKey) {
            int call = calls.incrementAndGet();
            return call <= n
                    ? Decision.reject(Duration.ofSeconds(7))
                    : Decision.allow();
        }
    }

    /** Audit 로그를 캡처 — 호출 회수 / actor / reason 검증. */
    static final class CapturingAuditLog implements AuditLogPort {
        final List<AuditEntry> entries = new ArrayList<>();

        @Override
        public void log(AuditEntry entry) {
            entries.add(entry);
        }

        Set<AuditAction> actions() {
            Set<AuditAction> seen = new HashSet<>();
            for (AuditEntry e : entries) seen.add(e.action());
            return seen;
        }
    }
}
