package com.example.market.adapter.web.dto

import com.example.market.application.command.RecordSellerShippingCommand
import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.TradeId
import jakarta.validation.constraints.NotBlank

data class RecordSellerShippingRequest(@field:NotBlank val trackingNumber: String) {
    fun toCommand(requestor: UserId, tradeId: TradeId) =
        RecordSellerShippingCommand(requestor, tradeId, trackingNumber)
}

/**
 * Cursor pagination 응답 wrapper (ADR-0025).
 *
 * - [items]: 현재 페이지의 항목들
 * - [nextCursor]: 다음 페이지 요청에 그대로 다시 보낼 opaque token. 없으면 마지막 페이지.
 *
 * 의도적으로 `totalElements` / `totalPages` 는 노출하지 않는다 — cursor 패턴의 본질이 *총 개수
 * 모름* + *임의 페이지로 점프 못 함* 이라.
 */
data class CursorPageResponse<T>(
    val items: List<T>,
    val nextCursor: String?,
)
