# ADR-0007: Outbox 패턴 — 이벤트 발행을 DB 트랜잭션과 묶기

## 상태
적용

## 배경
도메인 이벤트(TradeMatched, InspectionPassed 등) 를 Kafka 로 발행할 때, *DB 트랜잭션과 원자적으로* 보장돼야 한다. 그렇지 않으면 다음 두 문제가 생긴다.

- DB commit 성공 → Kafka publish 실패 → 이벤트 *유실*
- Kafka publish 성공 → DB commit 실패 → *유령 이벤트* (DB 에는 없는데 이벤트만 발행됨)

## 결정
**Outbox 테이블 + 별도 Relay.**

1. `EventPublisher.publish()` 가 *같은 트랜잭션* 의 outbox 테이블에 INSERT.
2. 별도 `OutboxRelay` 가 `@Scheduled` 폴링으로 unpublished row 를 읽어 Kafka 로 동기 send + `Future.get(timeout)`. 성공한 row 만 `markPublished` (별도 트랜잭션).
3. Kafka send 실패 시 markPublished 안 하고 다음 폴링에서 재시도.

## 장단점
- DB commit 시점에 이벤트 발행이 보장된다 (atomic).
- Kafka 가 다운돼도 outbox 에 누적됐다가 복구 후 일괄 publish.
- at-least-once 보장이라 컨슈머가 멱등하면 충분하다.
- 폴링 지연(기본 1초) 이 있다 — 더 짧은 실시간성이 필요하면 폴링 간격 조정.
- Outbox 테이블이 시간이 지나면서 비대해진다 — 발행 완료 row 는 보관 기간이 지난 뒤 정리 배치로 삭제한다.

## 다른 선택지
- **Spring Modulith Events (JPA 기반)**: 비슷한 효과지만 우리 Outbox 가 더 명시적이고 검증하기 쉽다.
- **Debezium CDC**: 현재 규모에서는 운영 복잡도에 비해 얻는 이점이 작다.
