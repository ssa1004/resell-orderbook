package com.example.market.application.dlq

import java.time.Instant

/**
 * Bulk replay / discard 요청. notification-hub ADR-0015 + billing ADR-0033 의 공통 패턴 —
 * 필터 조건으로 대상 집합을 결정하고, `confirm=true` 없이는 **dry-run 강제** (몇 건이 영향
 * 받는지만 계산해서 반환).
 *
 * `confirm=false` 인 dry-run 응답은 작업 ID 를 만들지 않고 [DlqBulkDryRunResult] 반환,
 * `confirm=true` 는 비동기 작업을 큐잉하고 [DlqBulkJob] 를 반환 — 운영자가 `GET
 * /admin/dlq/bulk-jobs/{jobId}` 로 폴링.
 *
 * @param source     필수 source 필터 — 한 번에 전 source 를 휘젓지 못하게 명시 강제
 * @param topic      optional 추가 토픽 필터
 * @param errorType  optional 예외 클래스 필터
 * @param from       optional 시간 하한
 * @param to         optional 시간 상한
 * @param skuId      market 특유 — 특정 SKU 만 (예: 한 sneaker 의 PG 장애 batch 해소)
 * @param confirm    false=dry-run, true=실제 실행
 * @param reason     DISCARD 인 경우 필수 — audit 에 기록
 * @param actor      운영자 식별자 — controller 가 JWT subject / X-Actor 헤더에서 채움
 */
@JvmRecord
data class DlqBulkRequest(
    val source: DlqSource,
    val topic: String?,
    val errorType: String?,
    val from: Instant?,
    val to: Instant?,
    val skuId: String?,
    val confirm: Boolean,
    val reason: String?,
    val actor: String,
) {
    init {
        require(actor.isNotBlank()) { "actor must not be blank" }
        if (from != null && to != null) {
            require(!from.isAfter(to)) { "from must be <= to" }
        }
    }
}

/**
 * dry-run 응답 — 실제 액션은 안 일어났고, 영향받을 메시지 수와 샘플만 반환.
 *
 * @param matched 필터에 매칭된 총 메시지 수
 * @param sample  미리보기용 상위 N 건 — 운영자가 의도가 맞는지 확인
 */
@JvmRecord
data class DlqBulkDryRunResult(
    val action: DlqAction,
    val source: DlqSource,
    val matched: Long,
    val sample: List<DlqMessage>,
)
