package com.example.market.domain.trading;

/**
 * 거래 라이프사이클. 자세한 다이어그램은 docs/adr/0004-trade-saga.md.
 *
 * <pre>
 *  CREATED (매칭 직후, 결제 대기)
 *    │   buyer authorize 결제
 *    ▼
 *  PAYMENT_AUTHORIZED
 *    │   판매자 발송 요청 알림
 *    ▼
 *  SELLER_SHIPPING (판매자가 검수센터로 발송 중)
 *    │   검수센터 도착
 *    ▼
 *  INSPECTION_PENDING
 *    │   검수 결과
 *    ▼
 *  ┌──────────────┬──────────────┐
 *  ▼              ▼
 *  INSPECTION_PASSED    INSPECTION_FAILED
 *    │                      │
 *    ▼                      ▼
 *  BUYER_SHIPPING        REFUNDING (구매자 환불 + 판매자 반송)
 *    │                      │
 *    ▼                      ▼
 *  COMPLETED (정산 완료)   FAILED
 * </pre>
 */
public enum TradeStatus {
    CREATED,
    PAYMENT_AUTHORIZED,
    SELLER_SHIPPING,
    INSPECTION_PENDING,
    INSPECTION_PASSED,
    INSPECTION_FAILED,
    BUYER_SHIPPING,
    REFUNDING,
    COMPLETED,
    FAILED;

    public boolean isTerminal() { return this == COMPLETED || this == FAILED; }
}
