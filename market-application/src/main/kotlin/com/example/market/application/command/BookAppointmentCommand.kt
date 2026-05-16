package com.example.market.application.command

import java.time.Instant
import java.util.UUID

/**
 * @param idempotencyKey  사용자 헤더에서 — 중복 클릭 방지
 * @param tradeId         예약 대상 Trade
 * @param centerId        검수 센터
 * @param desiredSlotTime 사용자가 원하는 시각. 도메인이 이 시각이 속한 *슬롯 시작* 으로 정렬.
 */
@JvmRecord
data class BookAppointmentCommand(
    val idempotencyKey: String,
    val tradeId: UUID,
    val centerId: UUID,
    val desiredSlotTime: Instant,
)
