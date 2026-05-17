package com.example.market.application.dlq

import java.time.Instant

/**
 * 단일 DLQ 메시지의 상세 — 본문 payload + 전체 stack trace + Kafka 메타데이터.
 *
 * 목록 ([DlqMessage]) 에 비해 무거우므로 단건 조회에서만 반환.
 *
 * @param payload       원본 메시지 JSON (Outbox publish 한 그 그대로)
 * @param stackTrace    실패 시 잡힌 예외 stack trace (전체)
 * @param headers       Kafka header 사본 — `X-Original-Topic`, correlation-id 등
 * @param partition     원래 토픽의 파티션 번호 (Kafka 의 경우)
 * @param offset        원래 토픽의 offset (Kafka 의 경우)
 * @param firstSeenAt   처음 DLQ 에 들어온 시각 ([DlqMessage.occurredAt] 와 동일)
 * @param lastSeenAt    가장 최근 시도 시각 — 운영자 replay 가 다시 실패하면 이 값이 갱신
 */
@JvmRecord
data class DlqMessageDetail(
    val summary: DlqMessage,
    val payload: String,
    val stackTrace: String,
    val headers: Map<String, String>,
    val partition: Int?,
    val offset: Long?,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
)
