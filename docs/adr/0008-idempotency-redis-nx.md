# ADR-0008: 멱등성 처리 이원화 — 사용자 요청은 Redis NX, 시스템 트리거는 상태 체크

## 상태
적용

## 배경
중복 요청은 두 가지 경로로 들어온다.

1. **사용자 요청** — 같은 ASK 두 번 등록, 같은 BuyNow 두 번. 거래가 중복으로 만들어질 수 있다.
2. **시스템 (이벤트 컨슈머) 트리거** — Kafka at-least-once 라 같은 PaymentAuthorize 가 두 번 호출될 수 있다.

## 결정
두 경로를 다르게 처리한다.

- **사용자 요청**: `Idempotency-Key` 헤더 필수. `IdempotencyKeyStore.acquireOrThrow(key)` 가 Redis NX (dev 는 in-memory) 로 점유한다. 같은 키로 다시 들어오면 `DuplicateRequestException` → HTTP 409.
- **시스템 트리거**: 컨슈머가 *현재 애그리거트 상태* 를 체크해서 자연스럽게 멱등하게 만든다. 예: `AuthorizePaymentService` 가 Trade 상태가 이미 PAYMENT_AUTHORIZED 이면 PG 호출을 건너뛴다.

## 장단점
- 사용자가 retry 해도 안전하다 — 같은 키로 다시 보내도 거래는 한 번만 발생.
- 시스템 멱등성은 별도 store 없이 도메인 상태가 진실이라 단순하다.
- 응답 캐시(같은 키 재요청 시 *기존 응답* 반환) 는 미구현이다 — 단순화를 위해 뺐다. 클라이언트는 409 받으면 별도 GET 으로 결과 확인하는 패턴.
- Redis 가 다운되면 사용자 멱등성 보장이 약해진다. DB unique constraint 같은 2차 방어선을 추가하면 더 안전하다.
