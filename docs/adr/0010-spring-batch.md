# ADR-0010: Spring Batch — 만료 호가 정리 + 결제 TTL 초과 거래 자동 취소

## 상태
적용

## 배경
- ASK/BID 는 30일 후 자동 만료된다. DB 의 `expires_at` 만 지나면 매칭이 되지는 않지만
  (`isMatchableAt` 검사), status 컬럼은 ACTIVE 로 남아 있다 — 정리 작업이 필요하다.
- Trade 가 매칭됐는데 PG 결제 승인이 늦어지는 경우 (TTL = Time-To-Live, 유효 기간 15분 초과)
  자동으로 취소해야 한다.

배치 처리 자체보다 부분 실패 허용, 재실행 가능, 이전 실행 추적이 핵심이다.

## 결정
**Spring Batch (Tasklet 모드 — 한 단계 안에서 코드 한 덩어리를 그대로 실행하는 단순 모드)**
로 작업 3개를 만든다.

- `StaleListingExpirationJobConfig` — 만료된 ACTIVE Listing 을 EXPIRED 로 일괄 마킹.
- `StaleBidExpirationJobConfig` — BID 도 동일.
- `AutoCancelStaleTradesJobConfig` — TTL 지난 CREATED 거래를 `cancelOnPaymentFailure` 로
  종료.

각 job 은 application 의 use case (`ExpireStaleListingsUseCase` 등) 를 호출한다 — 도메인
불변식 (항상 지켜져야 하는 규칙) 이 batch 코드로 새어나오지 않는다.

## 장단점
- 실패 시 Spring Batch 의 메타데이터 테이블 (실행 이력을 담는 별도 테이블 묶음) 이 자동으로
  추적해서 재실행이 안전하다.
- 청크 (chunk, 건수 단위로 끊어서 처리) + skip (특정 예외는 건너뛰기) 정책 추가 시 use case
  변경 없이 batch 설정만 수정하면 된다.
- Spring Batch 메타데이터 테이블이 H2/Postgres 에 자동 생성된다
  (`spring.batch.jdbc.initialize-schema=always`).
- 여러 Job 이 등록돼 있으면 시작 시 어떤 Job 을 실행할지 모호하다는 에러가 나서
  `spring.batch.job.enabled=false` 로 자동 실행을 막고, 코드에서 명시적으로 트리거한다.

## 트리거
운영 환경에서는 k8s CronJob (정해진 시간마다 새 Pod 를 띄워 작업을 실행하는 쿠버네티스
오브젝트) 으로 매시간/매일 트리거한다. Quartz 같은 자바 스케줄러를 추가해도 된다.
