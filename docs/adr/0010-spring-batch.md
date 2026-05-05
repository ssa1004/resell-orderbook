# ADR-0010: Spring Batch — 만료 호가 정리 + 결제 TTL 초과 거래 자동 취소

## 상태
적용

## 배경
- ASK/BID 는 30일 후 자동 만료된다. DB 의 `expires_at` 만 지나면 매칭이 안 되긴 하지만(`isMatchableAt` 검사), status 는 ACTIVE 로 남아 있다 — 정리 작업이 필요하다.
- Trade 가 매칭됐는데 PG authorize 가 늦어지는 경우(15분 TTL 초과) 자동으로 취소해야 한다.

배치 처리 자체보다 *부분 실패 허용*, *재실행 가능*, *이전 실행 추적* 이 핵심이다.

## 결정
**Spring Batch (Tasklet 모드)** 로 작업 3개를 만든다.

- `StaleListingExpirationJobConfig` — 만료된 ACTIVE Listing 을 EXPIRED 로 일괄 마킹.
- `StaleBidExpirationJobConfig` — BID 도 동일.
- `AutoCancelStaleTradesJobConfig` — TTL 지난 CREATED 거래를 `cancelOnPaymentFailure` 로 종료.

각 job 은 application 의 use case (`ExpireStaleListingsUseCase` 등) 를 호출한다 — 도메인 불변식이 batch 코드에 침투하지 않는다.

## 장단점
- 실패 시 Spring Batch metadata 가 자동으로 추적해서 재실행이 안전하다.
- chunk + skip 정책 추가 시 use case 변경 없이 batch config 만 수정하면 된다.
- Spring Batch metadata 테이블이 H2/Postgres 에 자동 생성된다 (`spring.batch.jdbc.initialize-schema=always`).
- 여러 Job 이 등록돼 있으면 시작 시 ambiguous 에러가 나서 `spring.batch.job.enabled=false` 로 막고, 명시적으로 trigger 한다.

## 트리거
운영 환경에서는 k8s CronJob 으로 매시간/매일 trigger 한다. Quartz 같은 scheduler 를 추가해도 된다.
