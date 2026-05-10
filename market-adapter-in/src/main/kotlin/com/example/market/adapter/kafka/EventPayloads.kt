package com.example.market.adapter.kafka

import com.example.market.domain.catalog.SkuId
import com.example.market.domain.trading.TradeId
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Outbox 가 발행한 도메인 이벤트 JSON 페이로드 파서.
 *
 * <p>Outbox 의 Jackson 직렬화 규칙: value object 는 `{"value": "<문자열>"}` 한 필드짜리
 * 객체로 떨어진다 (예: {@code TradeId} → {@code "tradeId": {"value": "uuid..."}}). 컨슈머마다
 * `node.get("tradeId").get("value").asText()` 보일러플레이트가 반복되던 것을 한 곳으로 모은다.</p>
 *
 * <p>스키마가 바뀌면 (예: 평탄화 / 키 이름 변경) 이 파일 한 곳만 수정한다 — 전에 토픽 상수가
 * 컨슈머 4곳에 흩어져 typo 가 들어왔던 사고 ({@link MarketTopic}) 와 같은 방어선.</p>
 */
internal fun ObjectMapper.parseEvent(payload: String): JsonNode = readTree(payload)

/**
 * `{"<field>": {"value": "..."}}` 형태에서 안쪽 문자열을 꺼낸다.
 * 필드 자체가 없거나 `value` 가 없으면 null — 알림 대상 없는 이벤트 등에서 활용.
 */
internal fun JsonNode.valueOf(field: String): String? =
    get(field)?.get("value")?.asText()

/** 이벤트의 `tradeId.value` — 없으면 IllegalStateException. saga 처럼 항상 있어야 하는 경로용. */
internal fun JsonNode.requireTradeId(): TradeId =
    TradeId.of(valueOf("tradeId") ?: error("tradeId missing in event payload"))

/** 이벤트의 `skuId.value` — 없으면 IllegalStateException. 호가창 broadcast 경로용. */
internal fun JsonNode.requireSkuId(): SkuId =
    SkuId.of(valueOf("skuId") ?: error("skuId missing in event payload"))
