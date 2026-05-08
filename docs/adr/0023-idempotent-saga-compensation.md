# ADR-0023: SAGA 보상 트랜잭션의 명시 멱등성 — Compensation Log

## 상태
적용

## 배경

ADR-0004 의 거래 Saga 는 *코레오그래피* — Trade 의 단계별 이벤트 (TradeMatched / PaymentAuthorized
/ InspectionFailed / TradeCompleted ...) 가 Outbox → Kafka → 컨슈머 흐름으로 흐른다. 컨슈머는
**at-least-once** (적어도 한 번) — 같은 이벤트가 두 번 들어올 수 있다.

영향이 큰 두 보상 트랜잭션:

- `RefundBuyerService` — InspectionFailed 컨슈머 → PG.refund 호출 → Refund.complete
- `SettleTradeService` — TradeCompleted 컨슈머 → bankTransfer.send → Payout.send

두 서비스 모두 *외부 호출* (PG / 은행) 이 핵심. 외부 호출의 응답이 *유실* 되는 시나리오에서는
DB UNIQUE 만으로는 부족하다 — 첫 호출이 PG 까지는 도달했는데 응답이 도중에 끊긴 경우, 컨슈머가
재시도하면 PG 가 두 번 호출될 수 있다.

기존 코드는 다음 두 가지로 막고 있었음:

1. `findByTradeId` 로 기존 Refund / Payout 이 있으면 그대로 반환 (DB 기반 short-circuit)
2. `uk_payout_trade`, `uk_refund_trade` UNIQUE 로 두 번째 INSERT 거절

이는 **DB row 가 이미 commit 된 상태로 도착한 재시도** 만 막는다. *PG 호출 중에 응답 timeout
→ DB 트랜잭션 rollback → 메시지 재시도* 흐름에서는 PG 가 두 번 호출될 수 있다.

토스 / 카카오페이 / Stripe 의 결제 도메인 표준은 **명시 idempotency key + 결과 캐시** 패턴 —
외부 호출 직전에 idempotency key 로 자리를 점유 (DB row INSERT) 하고, 외부 응답을 받아 같은
row 에 결과를 박아둔다. 재시도 시 row 가 있으면 외부 호출 없이 캐시된 결과를 그대로 반환.

## 결정

### `compensation_log` 테이블

```sql
CREATE TABLE compensation_log (
    operation       VARCHAR(40),    -- "REFUND", "SETTLE_PAYOUT", "REFUND_RETRY", ...
    business_key    VARCHAR(100),   -- 일반적으로 tradeId
    status          VARCHAR(20),    -- IN_PROGRESS / COMPLETED / FAILED
    response_code   VARCHAR(40),
    response_message VARCHAR(500),
    external_id     VARCHAR(200),   -- pgRefundId / bankTransferId
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP,
    PRIMARY KEY (operation, business_key)
);
```

`(operation, business_key)` 가 합성 PK — 같은 보상 1건당 row 1개. 외부 호출 직전에 INSERT
(status=IN_PROGRESS), 결과를 받아 UPDATE.

### `CompensationGuard.runOnce(op, key, action)` helper

```java
public <T> Outcome<T> runOnce(String operation, String businessKey, Function<...> action) {
    var existing = store.find(operation, businessKey);
    if (existing.isPresent()) {
        if (existing.isCompleted())  return cachedFromEntry(existing);     // 캐시 hit
        if (existing.isInProgress()) throw new DuplicateInProgressException();
        if (existing.isFailed())     return cachedFailureFromEntry(existing);  // 재시도는 새 키로
    }
    store.begin(operation, businessKey, now);     // PK INSERT — 자리 점유
    try {
        outcome = action.apply(null);             // 외부 호출
    } catch (RuntimeException e) {
        store.fail(operation, businessKey, ...);
        throw e;
    }
    if (outcome.completed()) store.complete(...);
    else                     store.fail(...);
    return outcome;
}
```

- **첫 호출**: row 없음 → begin → action → complete/fail.
- **재호출 (캐시 hit)**: COMPLETED row 발견 → 외부 호출 *없이* 캐시된 externalId / 코드 / 메시지
  반환.
- **동시 두 thread 진입**: 한 명만 begin 성공, 두 번째는 PK 충돌 → DuplicateBeginException 후
  재조회. 다른 thread 가 IN_PROGRESS 면 DuplicateInProgressException 으로 호출자에게 신호 (메시지
  컨슈머는 자연스럽게 retry).

### Operation key 분리

- `REFUND` — 첫 환불 (RefundBuyerService)
- `REFUND_RETRY` — 운영자 재시도 (RetryRefundService) — 다른 operation key 라 같은 trade 의
  두 row 가 공존 가능
- `SETTLE_PAYOUT` — 정산 송금 (SettleTradeService)

### REQUIRES_NEW 트랜잭션

`JpaCompensationLogStore` 의 begin/complete/fail 은 `Propagation.REQUIRES_NEW` — 보상 트랜잭션의
메인 흐름이 commit/rollback 되더라도 compensation_log 자체는 별도로 박힌다. 외부 호출이 *실제
일어났는지* 의 단서가 메인 트랜잭션의 결과와 분리되어 추적 가능.

이 분리가 중요한 이유: action (외부 호출) 도중 예외 → 메인 트랜잭션 rollback. compensation_log
가 메인 트랜잭션 안에 있었다면 같이 rollback 되어 *첫 호출이 일어났던 흔적이 사라짐*. 다음
재시도는 자기가 처음인 줄 알고 또 PG 호출 → 중복.

### Outcome 의 result 필드

action 의 추가 결과 (도메인 객체 — 예: `PgClient.RefundResult`) 는 Outcome 에 담아 반환. 캐시
hit 시점에는 result == null 이 되어 호출자가 외부 식별자 (externalId) 만으로 도메인 객체를
재구성하는 책임을 진다. 이는 작은 트레이드오프 — result 자체를 DB 에 직렬화 보관하는 것 (toss
의 일부 PG 도 그렇게 함) 은 envelope 형식 관리 부담이 더 크다고 보고 일단 외부 식별자만 캐싱.

### 운영 모니터링

`status + started_at` 인덱스로 *IN_PROGRESS 가 오래 머무는 row* 를 쿼리. 이는 외부 호출이
stuck 인 신호 — 운영자가 수동으로 PG 의 트랜잭션 상태 조회 후 commit/cancel 결정.

## 대안 검토

- **DB UNIQUE 만으로 충분** — 응답 유실 시나리오에 약함 (위 배경 참조).
- **Redis SETNX 로 보상 키 점유** — Redis 가 죽으면 보상 자체가 안 됨 (fail-closed). DB 에 박는
  쪽이 정합성에 더 중요한 정보라 fail-open 가능.
- **PG idempotency key 만 외부에 넘기고 자체 로그 X** — PG 가 idempotency 를 지원해도 *응답이
  유실되는* 시나리오에서 자체 시스템이 외부 호출이 일어났는지 알 수가 없다. 자체 로그가 진실의
  원천.
- **Saga state machine 프레임워크 (Axon, Camunda, Eventuate Tram)** — overkill. 본 시스템의 보상은
  단계가 짧아 명시 helper 로 충분. + 라이브러리 의존이 도메인 코어까지 침투하면 헥사고날 경계가
  흐려짐.
- **EventLog table 에 "PG.refund called" 도 기록** — 같은 패턴이지만 row 가 다 따로 (call /
  response / completed) 라 *조회 비용* 증가. 본 ADR 의 1 row 1 보상 모델이 단순.

## 결과

- (장) PG / 은행이 *정확히 한 번* 호출됨을 명시 보장 — 응답 유실 시나리오에도 안전
- (장) `(operation, businessKey)` UNIQUE 로 동시 두 thread race 도 자연스럽게 처리
- (장) REQUIRES_NEW 로 메인 트랜잭션과 분리 — 외부 호출 흔적이 보존
- (장) operation 분리로 같은 trade 의 여러 보상 (REFUND / REFUND_RETRY / SETTLE_PAYOUT) 이 독립
- (장) helper 1개로 추상화 — 새 보상 흐름 추가 시 같은 패턴 재사용
- (단) DB row 1건이 매 보상 호출마다 추가 — 양은 미미 (= 거래 수)
- (단) FAILED 후 재시도는 *새 operation key* 권장 (RETRY: prefix) — RetryRefundService 의 기존
  흐름과 일치하지만 호출자가 알고 있어야 함
- (단) Outcome.result 가 캐시 hit 시 null — 호출자가 externalId 로 재구성. 일부 도메인 정보가
  유실될 수 있어 *외부 식별자만으로 일관 처리* 가 가능한 보상에만 적용

## 후속 후보

- *TTL / archive* — 90일 지난 COMPLETED row 를 별 archive 테이블로. compensation_log 자체가
  거래 수에 비례해 커지므로 hot path 인덱스 보호.
- *Outcome.result 직렬화* — JSON 으로 보관해 캐시 hit 시 도메인 객체 완전 복원. envelope 호환성
  필요.
- *분산 race 검증 IT* — 두 worker 가 동시에 같은 메시지를 처리하는 시나리오를 Testcontainer 로
  재현 (현재는 단위 테스트만).
- *operation 별 metrics* — Prometheus counter (operation, status) 라벨로 — 보상 실패율 / 캐시 hit
  비율 모니터링.
- *외부 PG idempotency key 통합* — PG 가 idempotency 를 지원하면 idempotency key 를 PG 에 같이
  넘겨 양쪽이 동기화. 이중 보호.
