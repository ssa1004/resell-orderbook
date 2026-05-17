package com.example.market.adapter.out.audit

import com.example.market.application.port.out.AuditLogPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 구조화 로그 기반의 감사 로그 어댑터 — 별도 SIEM / 테이블 통합 전 기본 구현.
 *
 * Logback 의 structured logger 설정 (예: JSON encoder) 와 결합하면 외부 로그 적재로
 * 자동 흘러간다. 운영 진화 시 JPA 또는 외부 SIEM 어댑터로 갈아끼울 수 있도록
 * `@ConditionalOnMissingBean` — 더 구체적인 빈이 있으면 본 어댑터가 비활성.
 *
 * 형식 (한 줄 KV) — Loki / ELK 의 logfmt parser 가 그대로 인덱싱:
 *
 * ```
 * audit action=DLQ_REPLAY actor=ops-alice target=msg-123 outcome=OK reason=null tradeId=t-1 skuId=sku-A
 * ```
 *
 * meta 는 JSON 으로 직렬화하지 않고 key=value 로 평탄화 — 정렬은 보장하지 않음.
 */
@Component
class Slf4jAuditLog : AuditLogPort {

    private val log = LoggerFactory.getLogger("audit")

    override fun log(entry: AuditLogPort.AuditEntry) {
        val metaPart = entry.meta.entries.joinToString(" ") { "${it.key}=${it.value}" }
        log.info(
            "audit at={} action={} actor={} target={} outcome={} reason={} tradeId={} skuId={} {}",
            entry.at, entry.action, entry.actor, entry.targetId,
            entry.outcome, entry.reason, entry.tradeId, entry.skuId, metaPart,
        )
    }
}
