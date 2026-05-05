# ADR-0004: 거래 Saga — 코레오그래피 (Outbox + Kafka)

## 상태
적용

## 배경
거래 라이프사이클은 매칭 → 결제 → 발송 → 검수 → 정산 의 7~10 단계 흐름이고, 외부 시스템(PG, 검수센터, 은행) 과 결합돼 있다.

분산 트랜잭션 스타일 두 가지 중 선택해야 한다.

- **오케스트레이션** — `SagaOrchestrator` 가 모든 단계를 순서대로 호출한다.
- **코레오그래피** — 각 단계가 도메인 이벤트를 발행하고, 다음 단계 컨슈머가 자기 책임을 수행한다.

## 결정
**코레오그래피 + Outbox 패턴.**

```
TradeMatched   → AuthorizePaymentService (PG.authorize)
PaymentAuth.   → SellerNotificationService (이메일/푸시)
InspectionPass → StartBuyerShippingService (자동 전이)
InspectionFail → RefundBuyerService (PG.refund)
TradeCompleted → SettleTradeService (Payout.schedule)
```

## 장단점
- 단일 실패점이 없다 — 오케스트레이터 한 대가 죽으면 전체가 멈추는 일이 없다.
- 새 단계 추가 시 새 컨슈머만 추가하면 되고 기존 코드는 손대지 않는다.
- 흐름 추적이 흩어진다 — OpenTelemetry trace 로 단계마다 span 을 연결하고, X-Request-Id 로 보완한다.
- Kafka at-least-once 보장이라 컨슈머 멱등성을 강제해야 한다 (현재는 트레이드 상태 체크 패턴 사용).

## 운영
- 실패한 메시지는 DLQ 토픽(`<원본>-dlt`) 으로 격리.
- `RetryRefundUseCase` (admin) 로 운영자가 수동 재시도 가능.
