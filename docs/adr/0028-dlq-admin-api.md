# ADR-0028: DLQ 관리 콘솔 백엔드 API

## 상태
적용

## 배경

거래 saga (ADR-0004) 는 코레오그래피 — 매칭 → 결제 인증 → 발송 → 검수 → 정산의 각 단계가
Outbox (ADR-0007) → Kafka → 컨슈머 흐름으로 진행된다. 컨슈머가 실패하면 Spring Kafka 의
`DefaultErrorHandler` + `DeadLetterPublishingRecoverer` (DlqHandlerConfig) 가 3회 재시도 후
`<원본>-dlt` 토픽으로 메시지를 보낸다.

기존에는 DLQ 메시지를 운영자가 Kafka CLI 로 직접 옮겨야 했다 — 즉시성이 떨어지고, 누가
무엇을 왜 옮겼는지 흔적이 남지 않으며, 같은 SKU 의 거래가 한꺼번에 stuck 되는 운영 사고
패턴 (예: 특정 sneaker 의 PG 응답 지연) 을 시각적으로 잡기 어려웠다.

## 결정

운영자가 콘솔에서 DLQ 메시지를 조회 / replay / discard 할 수 있는 REST API 8 개를 도입한다.
DTO / port / 서비스 / 컨트롤러 / 어댑터를 헥사고날 경계 (ADR-0002) 안에서 구성하고, 적용
표준은 사내 다른 도메인 (notification-hub / billing-platform) 에서 이미 굳힌 패턴을 그대로
따른다.

### 1. REST endpoint

```
GET    /api/v1/admin/dlq             ?source=&topic=&errorType=&from=&to=&skuId=&cursor=&size=
GET    /api/v1/admin/dlq/{messageId}
POST   /api/v1/admin/dlq/{messageId}/replay         body: { reason?: "..." }
POST   /api/v1/admin/dlq/{messageId}/discard        body: { reason: "..." }     ← 필수
POST   /api/v1/admin/dlq/bulk-replay                body: { source, topic?, errorType?, from?, to?, skuId?, confirm: false|true, reason? }
POST   /api/v1/admin/dlq/bulk-discard               body: { source, ..., confirm: false|true, reason: "..." }   ← reason 필수
GET    /api/v1/admin/dlq/bulk-jobs/{jobId}
GET    /api/v1/admin/dlq/stats        ?from=&to=&bucket=PT1H&source=&topSku=&topErrorType=
```

목록 / 통계는 `dlq.read` scope, replay / discard 는 `dlq.write`, bulk 는 `dlq.bulk` —
[AdminRateLimiter] 에서 scope 별 RPS 분리. 분당 read 120 / write 60 / bulk 10 (yml override).

전체 endpoint 는 Spring Security 의 `@PreAuthorize("hasRole('ADMIN')")` 와 [SecurityConfig]
의 URL 매칭 (`/api/v1/admin/**` ADMIN) 으로 다중 방어. 모든 액션은 [AuditLogPort] 에 actor /
target / reason / tradeId / skuId / 결과 코드가 기록되고, 운영 환경에선 구조화 logback +
SIEM 으로 흘러간다.

### 2. `DlqSource` 분류 (market 특유)

```kotlin
enum class DlqSource { MATCHING, SETTLEMENT, REFUND, INSPECTION, PG_WEBHOOK, OUTBOX }
```

거래 saga 의 각 단계 + outbox relay 자체 + 외부 PG webhook 의 6 source 로 나눈다. notification-
hub / billing 의 단일 source 와 다른 점은 본 도메인이 결제 / 검수 / 정산이 독립된 consumer 로
동작 (ADR-0004) 한다는 것 — source 를 분리해야 stuck 위치가 즉시 잡힌다.

bulk 요청은 `source` 가 enum 필수 필드 — 한 번의 호출로 여러 source 를 휘젓지 못하게 강제
(운영 사고 폭발 반경 제한).

### 3. `DlqStats.bySku` 차원 (market 특유)

```kotlin
data class DlqStats(
    ...,
    val bySource: Map<DlqSource, Long>,
    val byErrorType: List<DlqErrorTypeCount>,
    val bySku: List<DlqSkuCount>,                 // ← market 특유
)
```

같은 SKU 의 거래가 한꺼번에 stuck 되는 패턴 — 예: 한정판 sneaker A 의 결제 webhook 이 PG
장애로 100건 동시 떨어짐, 검수센터 정전으로 SKU B 의 검수 후속 처리 50건 stuck — 을 직관적
으로 잡기 위한 ranking. notification-hub / billing 에는 없는 차원.

운영 시뮬레이션: bulk-replay 의 dry-run 응답에서 `matched` 수를 보고, stats 의 `bySku` 1위
가 매치하면 그 SKU 1개만 골라 다시 호출 (`skuId` 필터). 한 번의 PG 복구로 100건이 동시에
풀리는 흐름.

### 4. bulk replay / discard 의 dry-run 강제 + async-job 폴링

`confirm=false` (또는 누락) 이면 항상 dry-run — 필터에 매칭되는 메시지 수와 샘플 20건만
반환, 실 처리 안 함. 운영자가 영향 범위 확인 후 `confirm=true` 로 다시 호출하면 비동기 작업
큐잉 + `DlqBulkJob` 반환. 운영자는 `GET /admin/dlq/bulk-jobs/{jobId}` 로 진행률 폴링.

async worker 는 별 [ThreadPoolTaskExecutor][DlqBulkExecutorConfig] (`dlq-bulk-` prefix,
core 4 / queue 32) — 일반 요청 thread 와 격리. 한 메시지의 실패가 작업 전체를 중단하지 않게
chunk 별로 try/catch + status increment.

### 5. saga 멱등성과의 결합

replay 가 trade 1건을 두 번 환불 / 두 번 정산하지 않는 안전성은 ADR-0023 의 compensation_log
가 책임진다 — DlqAdminService 가 별도 idempotency 를 둘 필요 없다.

- REFUND replay : `RefundBuyerService` 가 `(operation=REFUND, businessKey=tradeId)` 로 PK
  점유. 이미 COMPLETED row 가 있으면 PG 호출 없이 캐시된 결과 반환.
- RetryRefund   : `RetryRefundService` 가 새로 만든 retry Refund 의 id 를 businessKey 로
  (Round 3 의 `b6de7d7` fix) — 매 retry 가 자기 row 를 갖고 PG 를 실제로 호출.
- SETTLE_PAYOUT : `SettleTradeService` 가 `(SETTLE_PAYOUT, tradeId)` 로 점유 — 은행 송금
  중복 방지.
- 그 외 saga step (authorize / startBuyerShipping / inspection result / settle) 도 use case
  내부에서 도메인 상태 체크로 멱등 (예: Trade.status 가 이미 진행 상태면 그대로 반환).

이 분담 덕분에 DlqAdminService 가 replay 대상 메시지의 도메인 의미를 알 필요가 없다 —
원래 토픽으로 재발행만 하면 안전.

### 6. hard delete 차단 + retention 후 auto-purge

discard 는 [DlqMessageStore.discard] 로 soft delete (status=DISCARDED) 만 수행. 실 hard
delete 는 retention 기간 (yml 설정, 기본 14일) 지난 row 를 `purgeBefore(threshold)` 로
별도 worker 가 일괄. compensation_log 가 회계 row 를 보존하는 정책 (ADR-0023) 과 일관 —
"운영자 사유 + 누가 / 언제" 흔적이 retention 안에는 살아 있다.

### 7. 어댑터 구성 (헥사고날)

| Port | dev 어댑터 | prod 어댑터 |
|---|---|---|
| `DlqMessageStore` | `InMemoryDlqMessageStore` (Kotlin) | `KafkaDlqMessageStore` (Kotlin, 스켈레톤 — 후속) |
| `DlqBulkJobRepository` | `InMemoryDlqBulkJobRepository` | (prod 도 in-memory, 후속에서 JPA) |
| `AdminRateLimiter` | `InMemoryAdminRateLimiter` | `RedisAdminRateLimiter` (Lua atomic) |
| `AuditLogPort` | `Slf4jAuditLog` (구조화 로그) | `Slf4jAuditLog` + SIEM forwarder |

`market.dlq.store.kafka.enabled=false` (dev 기본) 면 InMemoryDlqMessageStore, true 면 Kafka
어댑터로 자동 교체. KafkaDlqMessageStore 는 현재 스켈레톤 — DLT 토픽 listener + JPA 저장이
운영 진입 시점에 채워질 자리.

`RedisAdminRateLimiter` 는 사용자 facing limiter (ADR-0020) 와 같은 Lua atomic token bucket
이지만 **fail-closed** — Redis 가 죽으면 admin 액션을 잠근다. 사용자 facing 은 fail-open
(가용성 우선) 이지만 admin 의 잘못된 bulk 호출 폭발 반경이 더 크기 때문.

## 다른 선택지

- **Kafka CLI / Kafka UI 만 활용**: 의도적 흐름 + audit 기록이 없어 누가 무엇을 왜 옮겼는지
  추적 불가. 같은 SKU 동시 stuck 패턴도 시각적으로 잡기 어렵다.
- **Spring Modulith Events 의 publication 재시도**: 본 도메인 outbox 는 자체 구현 (ADR-0007)
  이라 통합 없음. + Modulith publication 은 단일 인스턴스 한정이라 prod 다인스턴스 환경 비
  적합.
- **단일 source 로 통일**: notification-hub / billing 의 단순 모델. 본 도메인은 saga 단계가
  6개로 분기해 위치 식별이 운영의 핵심 — source 분리 필요.
- **즉시 hard delete**: 운영자 실수의 폭발 반경이 크다 (회계 row 도 같이 삭제). retention
  + soft delete 가 안전.
- **bulk 호출 시 confirm=true 가 기본**: dry-run 강제가 표준 — 운영자가 영향 범위를 우선
  확인해야 한다는 외부 정책 일관성 + 실수 폭발 반경 제한.

## 결과

- (장) saga 의 stuck 메시지를 콘솔에서 직접 조회 / 처리 — Kafka CLI 의존 제거.
- (장) `source` 별 분류 + `bySku` ranking 으로 운영 사고 패턴 (PG 응답 지연, 검수센터 정전 등)
  을 시각적으로 식별.
- (장) bulk dry-run 강제 + async-job 폴링으로 운영자 실수의 폭발 반경 제한.
- (장) ADR-0023 의 compensation_log 가 replay 의 멱등성을 책임지므로 admin 코드가 도메인
  의미를 모를 수 있다 (단순화).
- (장) Kafka 어댑터 vs in-memory 가 같은 port 뒤에 있어 dev / prod 의 코드 경로 일치.
- (단) Kafka 어댑터가 현재 스켈레톤 — 운영 진입 전 DLT listener + JPA 적재가 별 후속 필요
  (Round 5 후속).
- (단) bulk job 의 진행 상태가 in-memory — 인스턴스 재시작 시 작업이 lost. prod 다인스턴스
  에서는 JPA / Redis store 로 교체 (후속).
- (단) hard delete 가 retention 기간 후에만 일어나므로 운영자가 즉시 "삭제 확인" 을 받을 수
  없다 — soft delete 의 의미를 가이드에 명시 필요.

## 후속 후보

- **KafkaDlqMessageStore 실 구현**: DLT 토픽 wildcard listener (`market.*-dlt`) → JPA
  `dlq_message` 테이블 (인덱스: `(source, occurred_at)`, `(sku_id, occurred_at)`, `(status,
  occurred_at)`) 적재. replay 가 원래 토픽 (`-dlt` 제거) 으로 KafkaTemplate.send.
- **DlqBulkJob JPA store**: 인스턴스 재시작에도 작업 추적이 유지되고, 운영자 콘솔이 "최근 24h
  의 모든 작업" 을 페이지로 보여줄 수 있게.
- **payload 파서**: DLT 메시지 payload 의 JSON 에서 tradeId / skuId 를 자동 추출 (EventPayloads
  의 parser 재사용) — 현재 stub.
- **DLQ → Slack/PagerDuty alert**: 특정 source 의 적재 속도가 threshold 초과 시 자동 알림.
- **Reaper 배치**: retention 지난 DISCARDED row 의 hard delete 를 매일 새벽 별 Spring Batch
  job 으로.
- **운영자 콘솔 UI**: 본 ADR 의 백엔드 API 를 소비하는 admin SPA — 본 ADR 의 scope 아님.

## 참고

- notification-hub ADR-0015 (admin v2) — 표준 endpoint + dry-run / async-job 패턴의 원형.
- billing-platform ADR-0033 — billing 특유 (DlqSource enum / byCustomer stats / hard delete
  차단) — 본 ADR 의 hard delete 차단 + audit 분리 정책의 기반.
- ADR-0004 (saga choreography) — 본 ADR 이 보호하는 흐름.
- ADR-0007 (outbox pattern) — outbox 자체의 DLQ 흐름.
- ADR-0023 (compensation log) — replay 의 멱등성을 보장하는 인접 정책.
- ADR-0020 (token bucket rate limiter) — admin scope 분리의 원형 알고리즘.
