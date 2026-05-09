# ADR-0026: 외부 호출 retry — exponential backoff + jitter

## 상태
적용 (PG / 은행 / 검수센터 instance 정의)

## 배경

PG (결제) / 은행 송금 / 검수센터 같은 외부 호출은 *간헐적 transient 오류* 가 일상적으로 발생한다 —
LB 의 짧은 시간 503, 네트워크 블립으로 인한 SocketTimeout, 컨테이너 재시작으로 인한 일시
ConnectException. 한두 번 재시도하면 자연스럽게 성공하는 경우가 대다수.

문제는 *어떻게* 재시도하느냐.

```
시나리오 — 외부 PG 가 5분간 부분 장애. 100개 client pod 가 같은 시점에 503 을 받음.

(나쁜 retry) 모두가 정확히 200ms 후에 다시 시도
  → 외부 시스템에 *같은 burst* 가 한 번 더 도착 → 또 503 → 또 같이 재시도
  → 한 번의 사고가 "100% 장애 → 회복 → 100% 장애" 의 oscillation

(좋은 retry) 200ms 의 ±50% 안에서 random
  → 재시도가 시간축에 펼쳐져 외부 시스템이 점진 회복할 여유를 받음
```

전자가 *thundering herd* (회복 시점에 다시 한 번에 몰리는 현상) 패턴. AWS 의
[Exponential Backoff and Jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)
(2015) 가 정리한 *backoff + jitter* 가 외부 호출 retry 의 사실상 표준 처방이 되었다.

ADR-0009 에서 PG 호출에 Resilience4j `@Retry` 를 적용했지만, 당시 정책은 `enableExponentialBackoff: true`
까지였고 jitter 가 없었다. 이 ADR 은 *jitter 추가* 와 *retry 대상의 화이트리스트* 를 정한다.

## 결정

### 표준 retry 정책

```yaml
resilience4j.retry.instances.pg:
  maxAttempts: 3                          # 첫 시도 + 재시도 2회
  waitDuration: 200ms                     # 첫 재시도 base wait
  enableExponentialBackoff: true
  exponentialBackoffMultiplier: 2         # 200ms → 400ms → 800ms
  randomizedWaitFactor: 0.5               # base wait 의 ±50% 범위에서 random
  retryExceptions:
    - java.io.IOException
    - java.net.SocketTimeoutException
    - org.springframework.web.client.HttpServerErrorException     # 5xx
    - org.springframework.web.client.ResourceAccessException      # connect 실패 / 타임아웃
  ignoreExceptions:
    - org.springframework.web.client.HttpClientErrorException     # 4xx — retry 무의미
```

### 왜 jitter 가 필요한가 — *full / equal / decorrelated*

AWS 가 정리한 jitter 의 세 형태:

| 종류 | 식 | 분산 정도 | 단점 |
|---|---|---|---|
| **No jitter** | `wait = base × 2^n` | 0 | 회복 시점에 retry 가 동시에 몰림 |
| **Full jitter** | `wait = random(0, base × 2^n)` | 0 ~ 최대 | base 보다 짧게 시도되어 외부 부담 ↑ 가능성 |
| **Equal jitter** | `wait = base × 2^n / 2 + random(0, base × 2^n / 2)` | base 의 절반 이상 ~ base 까지 | 분산 약함 |
| **Decorrelated** | `wait = min(cap, random(base, prev × 3))` | wide | 구현 복잡 |

Resilience4j 의 `randomizedWaitFactor` 는 `wait × (1 ± factor)` 형태로 *equal jitter 와 가까움*.
운영에선 *no jitter* 만 피하면 큰 차이가 안 나서 — 라이브러리 default 형태를 그대로 사용.
factor `0.5` 면 `100~300ms` 범위, 100개 client 가 같은 시점에 retry 해도 200ms 구간에 균등
분산.

### Retry 대상의 화이트리스트 — 5xx 만, 4xx 는 절대 retry 안 함

|  HTTP status | 의미 | retry? |
|---|---|---|
| 4xx (400, 401, 403, 404, 422 …) | client 잘못 — 요청이 잘못됐거나 인증 실패 | **X** (재시도해도 같은 결과) |
| 5xx (500, 502, 503, 504) | 서버 측 일시 오류 | **O** |
| Network IOException / SocketTimeout | transient 네트워크 문제 | **O** |

명시적 `retryExceptions` 화이트리스트 + `ignoreExceptions` 블랙리스트 를 둘 다 명시 — 한쪽만
두면 *분류되지 않은 예외* 가 default 정책에 따라 갈팡질팡한다. 둘 다 두면 의도가 명확하고
*모르는 새 예외* 가 추가될 때 retry 안 함 → 안전.

### maxAttempts 산정

```
사용자 응답 timeout 예산 ≥ Σ (각 시도의 wait + 외부 호출 latency)
```

본 도메인의 상한:

- PG: 5초 안에 결과 — wait 200 + 400 = 600ms + 호출 3회의 외부 latency. p99 200ms × 3 = 600ms
  → 600 + 600 = 1.2s. 사용자 timeout 5s 안에 충분히 들어옴 → maxAttempts 3.
- 은행: 10초 timeout — wait 500 + 1000 = 1.5s + 호출 3회 × 평균 1s = 4.5s. 합 6s → maxAttempts 3.
- 검수센터: 5초 — wait 300 + 600 = 900ms + 호출 3회 × 평균 500ms = 2.4s. maxAttempts 3.

maxAttempts 를 무작정 늘리면 *사용자 응답이 늦어지면서 외부에는 더 많은 부하* — *재시도가
멱등성 보장* 후에만 안전한 점도 함께 확인.

### 데코레이터 chain — Retry 가 *가장 안*

ADR-0021 의 표준 권장 순서: **Bulkhead → CircuitBreaker → Retry** (외부 → 내부).

```
사용자 호출
   ↓
[Bulkhead]   풀 포화 시 즉시 거절 (servlet thread 보호)
   ↓
[CircuitBreaker]   실패율 누적 시 회로 OPEN (외부 보호)
   ↓
[Retry]   transient 흡수
   ↓
실제 외부 호출
```

retry 가 *가장 안* 인 이유:

1. CircuitBreaker 가 open 상태면 retry 가 의미 없다 — 회로가 호출 자체를 막아서.
2. retry 의 실패가 CB 의 실패 카운트로 누적 — 외부 시스템이 정말 죽으면 *몇 번 retry 후* CB 가
   OPEN.
3. Bulkhead 는 retry 전에 풀에 자리가 있는지 보장 — 자리 없으면 retry 시도조차 안 함.

본 시스템은 raw `RestPgClient` 메서드에 `@CircuitBreaker + @Retry` 가 있고 (Resilience4j AOP 가
순서를 자동 적용 — `@Retry` 가 더 안), 그 메서드 호출이 `BulkheadedPgClient` 데코레이터 안에서
일어난다 — 자연스럽게 `Bulkhead → CB → Retry` 순서가 형성된다.

### 멱등성 — retry 안전성의 전제

retry 는 *같은 호출을 두 번 보낼 가능성* 을 만든다. 외부 API 가 멱등이 아니면 *결제가 두 번
일어나는* 사고. 본 시스템의 모든 외부 호출은 `idempotencyKey` 를 함께 보내고 — 외부 PG / 은행
모두 *같은 key 의 같은 요청은 한 번만* 실행하는 표준 인터페이스를 따른다 (Stripe Idempotent
Requests 가 대표 사례).

ADR-0008 의 클라이언트 측 idempotency-key store 와 별개로, *외부 API 측에도* idempotency-key 를
보내 *외부 시스템이 멱등 보장* 을 하도록 한다. 양쪽 멱등성이 모두 있어야 retry 가 안전.

## 대안 검토

- **No retry** — transient 오류가 모두 사용자 5xx 로 노출 → 운영 메트릭 5xx rate 가 외부 짧은
  블립에 노출. 외부 SLA 가 99.5% 라도 client SLA 는 그것보다 못함.
- **No backoff (즉시 재시도)** — 외부 부담을 첫 시도와 같은 강도로 즉시 한 번 더. 외부 회복 여유
  주지 못함.
- **No jitter** — 위 시나리오의 회복 시점 동시 retry. 단일 인스턴스 부하 테스트에선 안 보이고
  다수 pod 운영에서 첫 사고 때 드러남.
- **Spring Retry (`@Retryable`)** — Resilience4j 와 같은 기능을 별 라이브러리로 제공. 본 시스템은
  이미 Resilience4j 를 CircuitBreaker / Bulkhead 에 사용 중이라 *같은 라이브러리* 로 통일.
- **DLQ (Dead Letter Queue) 로 fallback** — retry 실패한 요청을 별 토픽으로 보내 재처리. write
  계열 (송금 / 정산) 에 적합. 본 ADR 의 retry 는 *동기 사용자 호출* 에 한정 — 결제 fail 시
  DLQ 보다 즉시 4xx 응답이 적합.

## 결과

- (장) Transient 오류가 사용자 응답 5xx 로 노출되지 않고 *조용히* 회복
- (장) jitter 로 *전체 pod 동시 retry* 방지 — 외부 시스템 회복 시점에 retry 가 시간축에
  분산되어 부드러운 ramp-up
- (장) 4xx 는 명시적으로 retry 안 함 — *클라이언트 잘못* 을 외부에 반복 부담하지 않음
- (장) ADR-0021 (Bulkhead) + ADR-0009 (CB) 와 자연스럽게 결합 — Bulkhead → CB → Retry 표준 순서
- (단) retry 횟수만큼 *같은 사용자 요청* 의 latency 증가 가능 — maxAttempts 3 + jitter 로 worst
  case ~1초 추가. 사용자 timeout 예산 안에서 cap
- (단) 멱등성 *전제* — 외부 API 가 idempotency-key 를 정확히 처리하지 않으면 retry 가 부작용
  (이중 결제). 외부 계약 시 반드시 확인 (양쪽이 *반드시* 멱등)
- (단) 외부 측 client SLA 메트릭 (`resilience4j.retry.calls`) 모니터링 필요 — retry rate 가 갑자기
  올라가면 외부 시스템 부분 장애 신호로 alert 트리거

## 운영 메트릭

Resilience4j 가 자동 export 하는 메트릭 (Micrometer):

| 메트릭 | 알람 기준 (예시) |
|---|---|
| `resilience4j.retry.calls{kind=successful_with_retry}` | rate ↑ → 외부 transient 증가 신호 |
| `resilience4j.retry.calls{kind=failed_with_retry}` | rate ↑ → 외부 *실제* 장애 신호 (CB OPEN 임박) |
| `resilience4j.retry.calls{kind=successful_without_retry}` | normal — 첫 시도 성공 |

`successful_with_retry / total` 의 비가 평소 0.5% 정도 — 이게 5% 가 1분 지속하면 외부 시스템
경보. CB OPEN 보다 *전조* 신호로 더 빠르게 잡힘.

## 후속 후보

- *Decorrelated jitter* — Resilience4j 가 직접 지원하진 않음. 사고 빈도가 잦은 외부 API 가
  생기면 IntervalFunction 직접 구현해서 적용
- *Adaptive timeout* — 외부 응답 latency 기반으로 호출별 timeout 동적 조정. Netflix concurrency-limits
- *Retry budget* — 단위 시간당 *총 retry 시도 횟수* 에 cap. 외부 장애 길어질 때 retry 자체가 다른
  trans 흐름까지 침식하는 것 방지 (gRPC / Envoy 의 retry budget 패턴)
- *DLQ 로의 자동 회수* — 사용자 응답이 끝난 뒤에도 송금 같은 *반드시 성공해야 하는* 호출은
  비동기 워커가 retry budget 안에서 계속 시도
