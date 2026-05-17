package com.example.market.application.dlq

import java.time.Instant

/**
 * DLQ 메시지의 목록 조회용 요약 DTO — `GET /api/v1/admin/dlq` 응답의 한 행.
 *
 * 본문 payload 는 [DlqMessageDetail] 에서만 노출 (목록 응답이 비대해지는 것을 막는다).
 *
 * @param messageId DLQ 어댑터가 부여한 고유 식별자 (Kafka 의 경우 `topic:partition:offset`)
 * @param source     출처 분류 — [DlqSource]
 * @param topic      원래 토픽 (`-dlt` suffix 제외한 정규화된 이름)
 * @param errorType  실패 원인의 예외 클래스 simple name — 그룹핑/필터의 기준
 * @param errorMessage 실패 메시지 첫 줄 — 긴 stack trace 는 상세 조회에서만
 * @param occurredAt DLQ 로 떨어진 시각
 * @param attemptCount 누적 시도 횟수 (consumer retry + 운영자 replay 합계)
 * @param tradeId   거래 ID — 거래 saga 가 출처면 채워짐, 그 외엔 null
 * @param skuId     SKU ID — 거래/매칭 흐름이면 채워짐. bySku 통계에 사용
 */
@JvmRecord
data class DlqMessage(
    val messageId: String,
    val source: DlqSource,
    val topic: String,
    val errorType: String,
    val errorMessage: String,
    val occurredAt: Instant,
    val attemptCount: Int,
    val tradeId: String?,
    val skuId: String?,
)
