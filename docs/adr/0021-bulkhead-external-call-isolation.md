# ADR-0021: 외부 호출 격리 — Resilience4j ThreadPoolBulkhead

## 상태
적용

## 배경

운영의 외부 호출 어댑터는 두 종류:

- `RestPgClient` — PG (결제 게이트웨이) HTTP API
- `MockBankTransferClient` — 판매자 정산 송금 (운영에선 실제 은행 어댑터로 진화)

둘 다 servlet thread (= 톰캣 워커, 사용자 요청을 받아 처리하는 메인 스레드) 안에서 동기 호출.
PG / 은행이 느려지면 응답을 기다리는 동안 servlet thread 가 그대로 점유된다. 이 상태가
누적되면:

1. 톰캣 풀이 다 차서 새 요청이 큐로 밀려난다
2. 호가창 / 검수 슬롯 같이 외부 호출과 무관한 endpoint 까지 같이 느려짐 (= cascade failure)
3. 하나의 외부 의존이 전체 시스템 가용성을 떨어뜨림

이것은 마이크로서비스 / 큰 모놀리스 양쪽에서 가장 흔한 운영 사고 패턴이다 — Netflix Hystrix
(2012) 가 이 문제를 부각시켰고, 후속 라이브러리 (Resilience4j, Sentinel) 가 기능을 단순화해
이어받았다.

ADR-0009 에서 PG 호출에 *Circuit Breaker + Retry* 를 이미 적용. 하지만 회로 차단은 *실패율
누적이 임계치를 넘은 뒤에야* 동작 — 그 전까지는 servlet thread 가 외부 응답을 기다리며
점유된다. 격리 (bulkhead) 는 *처음부터* 별 풀에 호출을 묶어서 servlet thread 가 점유되지
않게 한다.

## 결정

### Bulkhead — 선박의 격벽 비유

선박은 한 칸의 침수가 다른 칸까지 번지지 않게 격벽으로 나뉜다. 동일하게 — 외부 호출을
**전용 ThreadPool** 에 격리해 한 의존의 지연이 다른 흐름까지 번지지 않게 한다.

Resilience4j 의 두 종류:

| 종류 | 동작 | 적합 |
|---|---|---|
| Semaphore Bulkhead | 토큰 카운터 — 호출자 thread (servlet) 가 토큰 잡고 그대로 외부 응답 대기 | 짧은/CPU bound 작업 |
| **ThreadPool Bulkhead** | 별 풀에서 작업 실행, 호출자는 Future 만 await | **외부 HTTP/DB** |

PG/은행은 진짜 시간이 걸리는 외부 의존 → **ThreadPool Bulkhead** 가 적합. servlet thread 는
풀에 작업 제출 후 짧게 await 하다가 await timeout 으로 빠져나올 수 있다.

### 어댑터별 격리 풀

| 풀 이름 | 의도 | 코어 / 큐 / await |
|---|---|---|
| `pg`   | PG 결제 — peak 50 req/s × p99 200ms ≈ 동시성 10. 여유 50% 더해 코어 16. | 16 / 32 / 1s |
| `bank` | 판매자 정산 송금 — 호출 빈도 낮음 (정산 worker 가 직렬 가깝게). 여유 두고 코어 8. | 8 / 16 / 2s |

코어 산정은 *Little's law* — `필요 동시성 ≈ 도착률 × 평균 처리 시간`. 도착률은 운영 메트릭으로
결정, 평균 처리 시간은 외부 SLA. 거기에 burst 흡수용 30~50% 여유 추가.

큐 길이는 코어의 1~2배 정도. 너무 짧으면 burst 도 못 받음, 너무 길면 큐에서 대기하다 결국
사용자 요청 timeout 이 먼저 만료되어 queue 가 *이미 죽은 요청* 만 채워진다.

### 데코레이터 패턴 — `BulkheadedPgClient`, `BulkheadedBankTransferClient`

raw 어댑터 (`RestPgClient`, `MockBankTransferClient`) 는 그대로 두고, 그것을 wrap 하는
데코레이터를 application 측에 inject. application 코드는 `PgClient` 인터페이스만 알면 되어
격리가 들어와도 코드 변경 0건.

```java
public AuthorizeResult authorize(AuthorizeRequest req) {
    try {
        return bulkhead.execute(() -> delegate.authorize(req));
    } catch (BulkheadCapacityExceededException e) {
        return AuthorizeResult.rejected("BULKHEAD_FULL", "결제 시스템 부하");
    } catch (BulkheadAwaitTimeoutException e) {
        return AuthorizeResult.rejected("BULKHEAD_TIMEOUT", "결제 응답 지연");
    }
}
```

큐 포화 / await 시간 초과 → **도메인 거절 결과** 로 변환. application 코드는 이미 *PG 거절을
받는 표준 흐름* (Trade.cancelOnPaymentFailure 호출) 을 가지고 있어 일관 처리.

### 빈 등록 — Qualifier

raw 어댑터에 `@Component("rawPgClient")` / `@Component("rawBankTransferClient")` — 별 이름.
데코레이터 빈은 `@Primary` 로 등록. application 측이 `PgClient` 인터페이스로 inject 받을 때
`@Primary` 가 우선 선택. raw 빈은 살아있어 `@Qualifier("rawPgClient")` 로 우회 접근 가능
(테스트 / 운영 도구).

### 데코레이터 결합 순서

표준 권장: **Bulkhead → CircuitBreaker → Retry** (외부 → 내부).

- 가장 바깥 (Bulkhead): 코어 보호 — 풀 포화 시 즉시 거절
- 그 다음 (CircuitBreaker): 회로 — 실패율 누적 보호
- 가장 안 (Retry): 재시도 — 간헐적 실패 흡수

본 시스템은 raw `RestPgClient` 메서드에 `@CircuitBreaker + @Retry` 가 이미 있고,
`BulkheadedPgClient` 가 그것을 wrap — 자연스럽게 같은 순서가 된다.

### Failure mode

- **풀 포화 (BulkheadFullException)** → 도메인 거절 (BULKHEAD_FULL). 사용자에 503 + Retry-After.
- **Await timeout** → 도메인 거절 (BULKHEAD_TIMEOUT). 풀에는 들어갔으나 외부 응답이 너무 느려.
  본 호출의 servlet thread 만 빠지고 풀 안의 작업은 계속 진행 — 풀이 점차 회복.

`market.bulkhead.enabled=false` (dev 기본) 이면 데코레이터 자체가 미등록 — Mock 어댑터가 그대로
사용되어 격리 오버헤드 없음.

## 대안 검토

- **Semaphore Bulkhead** — 코어 단순. 하지만 servlet thread 가 외부 응답을 그대로 대기 → 격리
  효과 절반. 본 시스템은 외부 HTTP/DB 라 ThreadPool 이 맞다.
- **`@Bulkhead` annotation 직접 적용** — Resilience4j AOP 가 어댑터 메서드를 자동 wrap. 코드는
  더 짧지만 *어떤 풀이 어떻게 격리되는지* 가 코드에서 즉시 안 보인다. 명시 데코레이터가 의도
  명확.
- **요청 타임아웃만 짧게 (RestClient setReadTimeout)** — 외부 호출 자체는 짧게 끊지만 servlet
  thread 점유는 그대로. 근본 원인을 못 잡음.
- **별 ExecutorService + CompletableFuture** — 직접 만들 수 있지만 metric (Resilience4j 가
  `bulkhead.queue.depth` / `bulkhead.thread.pool.size` 등 자동 export) + 설정 외부화 (yml) 를
  새로 짜야 한다 — 라이브러리가 이미 같은 추상화 제공.

## 결과

- (장) 한 외부 의존 (PG) 의 지연이 다른 (호가창 / 검수) 까지 번지지 않음
- (장) 데코레이터 — application 코드 변경 0건
- (장) 풀 포화 시 도메인 거절로 fallback — 사용자에게 즉시 응답, 무한 대기 없음
- (장) raw 어댑터 + Qualifier 로 우회 접근 가능 (운영 도구 / 테스트)
- (단) 별 풀 thread context-switch 비용 (~수 μs). 외부 호출 RTT (수십 ms) 대비 무시 수준
- (단) 풀 코어 / 큐 산정이 *운영 메트릭 기반 추정* — 초기엔 보수적으로 잡고 관측하면서 조정 필요
- (단) 빈 등록이 Qualifier 로 갈라져 dev/prod 양쪽에서 raw 빈이 활성화 분기 (ConditionalOnProperty)

## 후속 후보

- *Adaptive concurrency* — 풀 코어를 외부 응답 latency 기반으로 동적 조정 (Netflix concurrency-limits
  의 Vegas 알고리즘). 사고 시점 자동 축소.
- *Per-tenant 격리* — 한 테넌트 (어카운트 / 파트너) 의 burst 가 다른 테넌트 풀까지 안 번지게.
- *가상 스레드와의 결합* — Java 21 의 virtual thread 위에 ThreadPoolBulkhead 의 carrier thread 풀로.
  코어 = 가상 스레드 수가 매우 커도 OS thread 부하 없음.
- *External SLO 메트릭 export* — Bulkhead queue depth / await time 을 Prometheus alert 로 → SRE
  대시보드.
