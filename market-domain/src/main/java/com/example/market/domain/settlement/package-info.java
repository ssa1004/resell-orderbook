/**
 * Settlement — 판매자 정산 (Payout) + 구매자 환불 (Refund) + 수수료 (Fee).
 *
 * <p>거래 금액 → 수수료 차감 → 판매자 정산. 검수 실패 시 구매자에게 전액 환불.</p>
 */
@org.springframework.modulith.NamedInterface("settlement")
package com.example.market.domain.settlement;
