# ADR-0009: PG 호출에 Resilience4j Circuit Breaker 적용

## 상태
적용

## 배경
외부 PG(결제 게이트웨이) 호출의 특성:

- 평균 100~500ms, P99 는 2초 넘기도 함.
- 가끔 5xx 가 burst 로 발생 (PG 측 장애).
- 우리 Trade 트랜잭션 안에서 호출되기 때문에 PG 가 hang 되면 우리 트랜잭션도 같이 hang.

## 결정
- `@CircuitBreaker(name = "pg")` + `@Retry(name = "pg")` + fallback method.
- 설정: slidingWindowSize=20, minimumNumberOfCalls=10, failureRateThreshold=50%, waitDurationInOpenState=30s.
- fallback 은 `AuthorizeResult.rejected("CB_OPEN", ...)` 를 반환 → application 계층의 `AuthorizePaymentService` 가 이 결과를 보고 `Trade.cancelOnPaymentFailure` 를 호출.

## 장단점
- PG 장애가 우리 Thread pool 을 잡지 않는다 — CB OPEN 상태에서는 즉시 실패한다.
- half-open 단계에서 3건 시도해 통과하면 자동으로 CLOSED 로 복귀한다.
- 일시적인 burst 로 fail rate 가 잠깐 올라가면 정상 호출도 거부될 수 있다 — `waitDurationInOpenState=30s` 로 짧은 시간에 회복.
- Retry 가 PG 의 멱등성을 가정한다 → `tradeId` 를 PG idempotency-key 로 사용해서 중복 결제 방지.

## 검증
`RestPgClientCircuitBreakerIT` 가 이 시나리오를 검증한다 — 4회 5xx 응답 후 CB OPEN, 다음 호출은 PG 에 도달하지 않고 fallback 으로 응답하는지.
