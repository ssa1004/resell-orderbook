package com.example.market.adapter.kafka

/**
 * Kafka 토픽 이름 상수.
 *
 * <p>Outbox publisher 의 규칙: {@code "market." + EventClass.simpleName.toLowerCase()}
 * (자세한 건 {@code OutboxRelay#relay()}). 이 객체의 상수도 같은 규칙을 따른다.</p>
 *
 * <p>예전에 이 값들이 4개 consumer 에 따로 하드코딩되어 있다가 한 곳에 typo
 * ({@code "market.trademetched"}) 가 들어와 saga 가 통째로 안 돌아간 적 있어
 * 한 곳으로 모았다. 이후로는 새 토픽 추가/이름 변경은 여기 한 군데만 건드린다.</p>
 */
object MarketTopic {

    // ── trading lifecycle ──────────────────────────────
    const val TRADE_MATCHED        = "market.tradematched"
    const val PAYMENT_AUTHORIZED   = "market.paymentauthorized"
    const val PAYMENT_REJECTED     = "market.paymentrejected"
    const val INSPECTION_PASSED    = "market.inspectionpassed"
    const val INSPECTION_FAILED    = "market.inspectionfailed"
    const val TRADE_COMPLETED      = "market.tradecompleted"
    const val REFUNDING_STARTED    = "market.refundingstarted"

    // ── orderbook 변경 ─────────────────────────────────
    const val LISTING_PLACED       = "market.listingplaced"
    const val LISTING_CANCELLED    = "market.listingcancelled"
    const val BID_PLACED           = "market.bidplaced"
    const val BID_CANCELLED        = "market.bidcancelled"
}
