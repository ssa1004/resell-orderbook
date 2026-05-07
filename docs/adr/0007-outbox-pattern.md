# ADR-0007: Outbox 패턴 — 이벤트 발행을 DB 트랜잭션과 묶기

## 상태
적용

## 배경
도메인 이벤트 (TradeMatched, InspectionPassed 등) 를 Kafka 로 발행할 때, *DB 트랜잭션과
함께 성공하거나 함께 실패해야* 한다. 그렇지 않으면 다음 두 문제가 생긴다.

- DB 커밋 성공 → Kafka 발행 실패 → 이벤트 *유실*
- Kafka 발행 성공 → DB 커밋 실패 → *유령 이벤트* (DB 에는 없는 일이 이벤트로만 나가버림)

## 결정
**Outbox 테이블 (보낼 이벤트를 잠시 저장해두는 테이블) + 별도 Relay (그 테이블을 읽어 실제로
브로커로 보내는 워커).**

1. `EventPublisher.publish()` 가 *같은 트랜잭션* 의 outbox 테이블에 INSERT — 도메인 변경과
   이벤트 저장이 한 번에 commit/rollback.
2. 별도 `OutboxRelay` 가 `@Scheduled` 로 주기적 폴링하며 미발행 row 를 읽어 Kafka 로 동기
   전송 후 `Future.get(timeout)` (브로커 응답을 기다림). 성공한 row 만 `markPublished`
   (별도 트랜잭션).
3. Kafka 전송 실패 시 markPublished 를 안 하고, 다음 폴링에서 재시도.

## 장단점
- DB 커밋 시점에 이벤트 발행이 같이 잡힌다 (둘이 함께 성공하거나 함께 실패).
- Kafka 가 다운돼도 이벤트가 outbox 에 쌓여 있다가 복구 후 한꺼번에 발행된다.
- 메시지가 최소 한 번 이상 전달되는 (at-least-once) 방식이라, 컨슈머가 같은 이벤트를 두 번
  받아도 문제없게 (멱등하게) 동작하면 충분하다.
- 폴링 지연 (기본 1초) 이 있다 — 더 짧은 실시간성이 필요하면 폴링 간격을 조정.
- Outbox 테이블이 시간이 지나면서 비대해진다 — 발행 완료된 row 는 보관 기간이 지난 뒤 정리
  배치로 삭제한다.

## 다른 선택지
- **Spring Modulith Events (JPA 기반)**: 비슷한 효과지만 우리 Outbox 가 더 명시적이고 검증
  하기 쉽다.
- **Debezium CDC (Change Data Capture, DB 의 변경 로그를 읽어 메시지로 흘려보내는 기술)**:
  현재 규모에서는 운영 복잡도에 비해 얻는 이점이 작다.
