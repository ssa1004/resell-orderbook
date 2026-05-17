package com.example.market.application.dlq

/**
 * DLQ 메시지가 어느 흐름에서 떨어졌는지 구분하는 출처 enum.
 *
 * 거래 saga (ADR-0004) 의 각 단계와 Outbox / PG webhook 같은 외부 이벤트 흐름을 source 로
 * 분류한다. notification-hub ADR-0015 의 단일 source 와 달리, 본 도메인은 결제/검수/정산이
 * 독립된 컨슈머라 source 를 분리해야 운영자가 stuck 위치를 빠르게 식별한다 (예: PG_WEBHOOK
 * 만 몰려 떨어지면 외부 게이트웨이 장애, REFUND 만 떨어지면 PG.refund API 장애 등).
 *
 * 새 source 를 추가할 땐 [topicHint] / [groupHint] 를 채워 운영 조회 시 토픽/groupId 와의
 * 매칭 단서로 쓴다.
 *
 * @property topicHint Kafka topic 이름 패턴 (DLT suffix 제외) — 운영자 검색의 단서
 * @property groupHint consumer group prefix — 같은 topic 을 여러 group 이 consume 할 때 분리
 */
enum class DlqSource(val topicHint: String, val groupHint: String) {

    /** TradeMatched → AuthorizePayment 단계 — 매칭 직후 PG 인증 실패가 stuck 되면 여기. */
    MATCHING("market.tradematched", "saga-authorize"),

    /** TradeCompleted → SettleTrade 단계 — 정산 송금 (bankTransfer) 실패가 stuck 되면 여기. */
    SETTLEMENT("market.tradecompleted", "saga-settle"),

    /** InspectionFailed → RefundBuyer 단계 — PG.refund 실패가 stuck 되면 여기. */
    REFUND("market.inspectionfailed", "saga-refund"),

    /** InspectionPassed → StartBuyerShipping 단계 — 검수 결과 후속 처리 실패. */
    INSPECTION("market.inspectionpassed", "saga-buyer-shipping"),

    /** PG webhook 처리 실패 — 외부 PG 가 보낸 결제 상태 변경 알림이 본 시스템에서 처리 안 됨. */
    PG_WEBHOOK("market.pgwebhook", "saga-pg-webhook"),

    /** Outbox relay 가 Kafka 발행에 반복 실패한 row — Outbox 테이블 자체의 DLQ 역할. */
    OUTBOX("market.outbox", "outbox-relay"),
    ;

    companion object {
        /**
         * 토픽 이름에서 source 를 추정 — KafkaDlqMessageStore 가 `-dlt` suffix 를 제거한 뒤
         * 매칭. 매칭 안 되면 null 반환 (어댑터가 OUTBOX 같은 기본값으로 fallback).
         */
        @JvmStatic
        fun fromTopic(topic: String): DlqSource? {
            val normalized = topic.removeSuffix("-dlt")
            return entries.firstOrNull { it.topicHint == normalized }
        }
    }
}
