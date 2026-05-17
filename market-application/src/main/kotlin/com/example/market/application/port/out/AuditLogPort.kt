package com.example.market.application.port.out

import java.time.Instant

/**
 * 운영자 액션의 감사 로그 — 우선 DLQ admin (replay / discard / bulk dry-run / start / finish)
 * 만 다룬다. 다른 admin endpoint 추가 시 [AuditAction] 에 enum 만 추가하면 같은 인프라 재사용.
 *
 * 별도 인터페이스인 이유 — 감사 로그는 본질상 *append-only* 라 도메인 흐름 (compensation_log)
 * 과 라이프사이클이 다르다. JPA 테이블 / Logback structured logger / 외부 SIEM 등으로 어댑터를
 * 자유롭게 갈아끼울 수 있게 분리.
 *
 * 어댑터 구현이 비활성이면 (dev) [NoopAuditLog] 가 기본 — log.info 로만 흘려보낸다.
 */
interface AuditLogPort {

    fun log(entry: AuditEntry)

    /**
     * @param at        액션 시각 (UTC)
     * @param actor     운영자 식별자 (JWT subject / IP)
     * @param action    [AuditAction] enum
     * @param targetId  대상 식별자 (messageId / jobId)
     * @param tradeId   거래 ID — saga 흐름 메시지면 채워짐
     * @param skuId     SKU ID — 거래/매칭 흐름이면 채워짐
     * @param reason    DISCARD / bulk discard 시 운영자가 적은 사유
     * @param outcome   성공 / 실패 / DRY_RUN 등 결과 라벨
     * @param meta      추가 컨텍스트 (matchedCount, source 등) — append-only JSON 형태
     */
    @JvmRecord
    data class AuditEntry(
        val at: Instant,
        val actor: String,
        val action: AuditAction,
        val targetId: String,
        val tradeId: String?,
        val skuId: String?,
        val reason: String?,
        val outcome: String,
        val meta: Map<String, String>,
    )

    enum class AuditAction {
        DLQ_REPLAY,
        DLQ_DISCARD,
        DLQ_BULK_DRYRUN,
        DLQ_BULK_START,
        DLQ_BULK_FINISH,
    }
}
