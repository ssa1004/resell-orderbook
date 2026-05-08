# ADR-0024: HikariCP 풀 크기 튜닝과 connection leak detection

## 상태
적용

## 배경

운영 첫날 가장 흔한 사고 한 가지 — *connection 풀 고갈* 때문에 모든 endpoint 가 멈춘다.
시나리오는 늘 비슷하다.

```
1. 어떤 코드 경로가 connection 을 빌리고 close() 를 빼먹는다 (또는 try-with-resources 누락)
2. 평소엔 호출이 적어 풀에 여유가 많아서 안 보임
3. 트래픽이 늘어 풀이 가득 차면, 그 다음 모든 요청이 connection-timeout 만큼 기다리다 fail
4. 호가창 / 검수 / 시세 — 누수가 일어난 곳과 무관한 endpoint 까지 같이 5xx
```

또 하나, *Spring Boot 의 HikariCP 기본값이 운영용이 아니다* — `maximum-pool-size: 10`,
`leak-detection-threshold: 0` (꺼짐) 이 default. 데모용 작은 값이라 그대로 운영에 올리면
peak 처리량이 풀에 묶인다. 큰 값이라고 무작정 좋은 것도 아니라서 — DB 측 `max_connections`
초과, lock contention 증가, context-switch 비용 등이 따라 온다.

이 ADR 은 두 가지를 정한다.

1. **풀 크기 산정 근거** — 추측이 아니라 *도착률 × 평균 트랜잭션 시간* 식으로.
2. **leak-detection-threshold 활성화** — 30초 보유 시 stack trace 강제 출력. 운영에서 누수가
   *발생한 그 코드* 가 바로 보여 디버깅 시간이 분 → 초.

## 결정

### 풀 크기 — Little's law 로 산정

기본 식:

```
필요 connection 수 ≈ 도착률(req/s) × 평균 트랜잭션 시간(s)
```

본 시스템 peak 가정:

| 워크로드 | 도착률 | 평균 트랜잭션 | 동시성 (= 도착률 × 평균) |
|---|---|---|---|
| API 요청 (호가 / 매칭 / 시세) | 60 req/s | 100ms | 6 |
| 매칭 worker (Kafka consumer) | — | — | +4 |
| Spring Batch (outbox / 정산) | — | — | +4 |
| OutboxRelay | — | — | +2 |
| 합 | | | 16 |

burst 흡수용 50% 여유 → **24**.

### 무조건 크면 좋다? 아니다

Brett Wooldridge (HikariCP 저자) 의 [About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
가이드는 *코어 수 × 2 + 디스크 spindle 수* 정도가 권장 상한이라고 얘기한다 (PostgreSQL 의
경우 디스크 spindle = SSD 기준 1). 코어 16 짜리 DB 면 ≈ 33. 그 이상은:

- DB 측 lock contention (특히 row-level / page-level lock 경쟁) 증가
- DB process 간 context switch 비용 누적 — 처리량은 오히려 감소하는 hump
- 메모리 (work_mem × connection 수) 폭발

짧은 트랜잭션 다수가 *적은 connection 을 빠르게 돌려쓰는* 편이 처리량과 latency 모두 유리.
풀이 작으면 일부 요청은 connection 을 잠깐 기다리지만 (Hikari 의 `connection-timeout`) DB 측
혼잡이 줄어 전체 처리량은 더 높다.

### DB 측 max_connections 와의 관계

```
운영 pod 수 × pod 당 풀 크기 ≤ DB 의 max_connections
```

예: PostgreSQL `max_connections = 200`, pod 8개 → pod 당 ≤ 25. 본 ADR 의 `24` 는 이 한계 안에
들어간다. 더 큰 fleet 으로 확장할 때는 풀 크기를 *줄이거나* DB 의 `max_connections` 를 올린
뒤 (메모리 영향 고려) 그에 맞춰 늘려야 한다.

### 명시 설정값

```yaml
spring.datasource.hikari:
  maximum-pool-size: 24
  minimum-idle: 8                # 새벽엔 풀이 8까지 줄어 idle connection 비용 절감
  connection-timeout: 3000       # 풀에서 connection 못 받으면 3초 만에 fail
  validation-timeout: 1000       # 살아있는지 검증 ping timeout
  idle-timeout: 600000           # 유휴 10분 후 회수 → minimum-idle 까지 축소
  max-lifetime: 1800000          # 30분 — DB / LB / NAT 측 timeout 보다 짧게 (만료 직전 끊기지 않게)
  keepalive-time: 300000         # 5분마다 ping — 방화벽 유휴 끊김 방지
  leak-detection-threshold: 30000   # 30초 보유 시 stack trace WARN
  register-mbeans: true          # JMX 노출 — Micrometer 외에도 직접 점검 가능
```

각 값의 의미:

- **connection-timeout 3s** — 풀이 가득 찼을 때 무한정 대기하지 않게. 사용자 입장에선 5xx 가
  *빨리* 오는 편이 *늦게* 오는 것보다 운영하기 쉽다 (LB 재라우팅 / 클라이언트 retry 가 동작).
- **max-lifetime 30분** — Postgres 의 `idle_in_transaction_session_timeout` 이나 클라우드 LB 의
  stickiness window 보다 짧아야 *우리가 먼저 끊는다*. 만료 직전에 쓰려는 connection 이 끊기는
  edge case 방지.
- **leak-detection-threshold 30초** — 정상 트랜잭션은 절대 30초를 안 넘는 본 도메인이라 false
  positive 위험 없음. 누수가 일어나면 *보유 시작 stack trace* 가 WARN 로 찍혀 코드 위치 즉시
  파악.

### dev (H2) 도 leak detection 만 활성

dev 는 풀이 작아도 (10) 누수 위험은 동일. leak-detection 만 켜면 단위/통합 테스트 단계에서
누수 코드가 PR 단계에서 잡힌다. CI 의 비용은 0 — 누수가 없으면 stack trace 도 안 찍힌다.

### 메트릭 / 운영 가시성

Spring Boot 가 자동 export 하는 Micrometer hikaricp.* 메트릭:

| 메트릭 | 알람 기준 (예시) |
|---|---|
| `hikaricp.connections.active` | `> maximum-pool-size × 0.9` 가 5분 지속 → 풀 부족 |
| `hikaricp.connections.pending` | `> 0` 이 1분 지속 → connection-timeout 위험 |
| `hikaricp.connections.usage` (히스토그램) | p99 가 평균 트랜잭션의 10배 초과 → 누수 후보 |
| `hikaricp.connections.timeout` | `rate > 0` → 풀 고갈 진행 중 |

Grafana 대시보드는 위 4개를 한 화면에 — 상시 점검 + alert 의 1차 그라운드.

## 대안 검토

- **Spring Boot default 그대로** — `maximum-pool-size: 10`, leak detection 꺼짐. 데모는 OK
  지만 peak 60 req/s × 100ms = 6 + worker/batch 까지 합치면 즉시 부족. 누수 발생 시 추적
  불가능. 운영에선 사실상 *후회 시점이 정해진* 선택.
- **Apache Commons DBCP / Tomcat JDBC** — Hikari 보다 느리고 (벤치마크 기준) leak detection
  옵션도 약함. Spring Boot 3 default 가 Hikari 인 이유.
- **PgBouncer (DB 측 connection pooler)** — 또 다른 layer. transaction-mode 에서는 prepared
  statement / advisory lock 같은 PostgreSQL 기능과 충돌 (본 시스템은 advisory lock 을 매칭
  직렬화에 쓴다 — ADR-0005). PgBouncer 를 도입하려면 session-mode 여야 하고 그러면 풀 효과
  반감. 우선은 어플리케이션 측 Hikari 만으로 운영, 트래픽 한계 도달 시 재검토.
- **풀 크기 자동 조정** — 동적 조정 라이브러리 (Hikari 자체엔 없음). 운영 메트릭으로 수동
  조정하는 편이 예측 가능. 자동 조정은 production stability 에 새 변수 추가.

## 결과

- (장) 풀 고갈 / connection 누수가 *흔한* 사고에서 *알람 + stack trace* 로 즉시 가시화
- (장) 풀 크기 산정이 *추측이 아닌 식* — 새 워크로드 추가 시 같은 식으로 재산정 가능
- (장) Micrometer 메트릭으로 grafana 알람 — 사고 *전* 에 풀 부족 신호 포착
- (단) `leak-detection-threshold` 자체 비용 — 매 borrow/return 시 ScheduledExecutorService
  task 등록/취소. 운영 부하 (수천 QPS) 에서도 유의미한 오버헤드 보고 없음
- (단) `max-lifetime` 으로 인한 connection 재생성 — 30분마다 풀 안에서 connection 이 회전.
  TLS handshake 비용이 있으나 Hikari 가 *active 가 아닌 idle connection* 만 만료시켜 사용자
  영향 없음

## 후속 후보

- *Connection acquire latency* 의 SLO 정의 — `hikaricp.connections.acquire` p99 가 100ms 를
  넘으면 풀 크기 재산정 트리거.
- *Read replica 분기* — read-only endpoint (시세 / 호가창) 만 별 풀로 보내면 write 풀이
  외부 PG 지연 같은 *write 측 사고* 에 더 강하게 격리됨. Spring 의 `AbstractRoutingDataSource`.
- *PgBouncer 도입* — fleet 이 더 커지고 (pod 수 × pod 당 풀 크기 ≥ DB max_connections) 일
  때. session-mode + advisory lock 호환성 확인 후.
- *Slow query alert* 와의 연계 — `hikaricp.connections.usage` 가 길어진 트랜잭션은 보통
  slow query 와 연결. pg_stat_statements 와 join 한 대시보드.
