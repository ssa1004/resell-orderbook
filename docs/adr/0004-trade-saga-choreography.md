# ADR-0004: 거래 Saga — 코레오그래피 (Outbox + Kafka)

## 상태
적용

## 배경
거래 라이프사이클은 매칭 → 결제 → 발송 → 검수 → 정산 의 7~10 단계 흐름이고, 외부 시스템
(PG = 결제 게이트웨이, 검수센터, 은행) 과 결합돼 있다.

여러 시스템에 걸친 트랜잭션을 어떻게 조정할지에 두 가지 스타일이 있다.

- **오케스트레이션** — `SagaOrchestrator` 라는 중앙 조정자가 모든 단계를 순서대로 호출한다.
- **코레오그래피** — 중앙 조정자 없이, 각 단계가 도메인 이벤트만 발행하고 다음 단계 컨슈머가
  스스로 자기 책임을 수행한다.

## 결정
**코레오그래피 + Outbox 패턴 (DB 트랜잭션 안에서 보낼 이벤트를 테이블에 함께 INSERT 한 뒤,
별도 워커가 그 테이블을 읽어 메시지 브로커로 보내는 패턴).**

```
TradeMatched   → AuthorizePaymentService (PG.authorize: 결제 승인)
PaymentAuth.   → SellerNotificationService (이메일/푸시)
InspectionPass → StartBuyerShippingService (검수 통과 시 구매자 배송 자동 시작)
InspectionFail → RefundBuyerService (PG.refund: 결제 취소/환불)
TradeCompleted → SettleTradeService (Payout.schedule: 판매자 정산 예약)
```

## 장단점
- 단일 실패 지점이 없다 — 한 대가 죽으면 전체가 멈추는 중앙 조정자가 없다.
- 새 단계 추가 시 새 컨슈머만 추가하면 되고 기존 코드는 손대지 않는다.
- 흐름 추적이 흩어진다 — OpenTelemetry trace (요청 하나가 여러 서비스를 거치는 경로를
  span 단위로 연결해 시각화하는 표준) 로 단계마다 span 을 연결하고, X-Request-Id 헤더로
  보완한다.
- Kafka at-least-once (메시지가 최소 한 번 이상 전달되며 중복 가능성 있음) 보장이라
  컨슈머가 멱등하게 (같은 메시지 두 번 받아도 결과 같게) 동작해야 한다. 현재는 거래 상태를
  먼저 체크해서 이미 처리됐으면 건너뛰는 방식 사용.

## 운영
- 실패한 메시지는 DLQ (Dead Letter Queue, 처리 실패한 메시지를 격리해두는 큐) 토픽
  `<원본>-dlt` 으로 격리.
- `RetryRefundUseCase` (admin) 로 운영자가 수동 재시도 가능.
