# ADR-0009: PG 호출에 Resilience4j Circuit Breaker 적용

## 상태
적용

## 배경
외부 PG (결제 게이트웨이, 카드사·은행과 연결되는 결제 중계 시스템) 호출의 특성:

- 평균 100~500ms, P99 (응답 시간 분포의 99 퍼센타일 = 100건 중 가장 느린 1건) 는 2초 넘기도
  한다.
- 가끔 5xx 가 짧은 시간에 한꺼번에 (burst) 발생 (PG 측 장애).
- 우리 Trade 트랜잭션 안에서 호출되기 때문에 PG 가 응답 없이 멈추면 (hang) 우리 트랜잭션도
  같이 멈춘다.

## 결정
- `@CircuitBreaker(name = "pg")` (실패율이 임계치를 넘으면 외부 호출을 차단해 자기 시스템을
  보호하는 회로 차단기) + `@Retry(name = "pg")` + fallback 메서드 (호출 실패 시 대신 실행할
  대체 응답).
- 설정: 최근 호출 20건의 윈도우에서 최소 10건 이상이 모이면 평가하고, 실패율 50% 초과 시
  차단, 차단 유지 시간 30초.
- fallback 은 `AuthorizeResult.rejected("CB_OPEN", ...)` 를 반환 → application 계층의
  `AuthorizePaymentService` 가 이 결과를 보고 `Trade.cancelOnPaymentFailure` 를 호출.

## 장단점
- PG 장애가 우리 스레드 풀 (요청 처리에 쓰는 스레드 자원) 을 잡아먹지 않는다 — 차단(OPEN)
  상태에서는 즉시 실패한다.
- 차단 → 살짝 열기 (half-open) 단계에서 3건 시도해 통과하면 자동으로 정상(CLOSED) 으로
  복귀한다.
- 일시적인 burst 로 실패율이 잠깐 올라가면 정상 호출도 거부될 수 있다 — 차단 유지 시간을
  30초로 짧게 잡아 빠르게 회복.
- 재시도는 PG 가 같은 요청을 두 번 받아도 한 번만 처리해주는 (멱등) 것을 가정한다 →
  `tradeId` 를 PG 의 idempotency-key (PG 측 중복 방지용 식별자) 로 사용해서 중복 결제 방지.

## 검증
`RestPgClientCircuitBreakerIT` 가 이 시나리오를 검증한다 — 4회 5xx 응답 후 차단 발동, 다음
호출은 PG 에 도달하지 않고 fallback 으로 응답하는지.

## 후속 보강 — ADR-0026 의 jitter / retryExceptions
초기 retry 정책은 *exponential backoff* 까지였다. 다수 pod 운영에서 *thundering herd* 위험이
있어 ADR-0026 에서 *randomizedWaitFactor* (jitter) 와 *retryExceptions / ignoreExceptions*
화이트리스트를 추가했다 (4xx 는 절대 retry 안 함). 본 ADR 의 결론 부분은 그대로 유효하고,
jitter / 화이트리스트 정책은 ADR-0026 으로 분리.
