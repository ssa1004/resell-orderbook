# Load test (k6)

bid-ask-marketplace 의 5 가지 부하 시나리오. 매칭 엔진 (advisory lock + FOR UPDATE SKIP LOCKED)
과 호가창 / 거래 내역 read-side 의 동작을 k6 로 검증한다. 단순 RPS 측정에 더해 매칭
엔진 특유의 invariant (응답 매칭 수 == DB Trade INSERT 수) 도 함께 본다.

## 디렉토리

```
load/
├── README.md
└── k6/
    ├── lib/
    │   ├── auth.js                  # X-User-Id + 선택적 Bearer 헤더
    │   └── config.js                # BASE URL, SKU pool, 가격 범위
    └── scenarios/
        ├── listing-create.js        # POST /api/v1/listings (ASK 등록)
        ├── order-match.js           # POST /api/v1/bids (BID 등록 + 매칭 트리거)
        ├── order-book-query.js      # GET /api/v1/orderbook/{sku}
        ├── trade-history.js         # GET /api/v1/trades/me/history (cursor)
        └── concurrent-match.js      # 동시 BID+ASK race — invariant 검증
```

## 사전 준비

세 가지 방법 중 하나로 k6 를 띄운다.

### A. brew 로 로컬 설치

```bash
brew install k6
k6 version
```

### B. docker 직접 실행

```bash
docker run --rm -i grafana/k6 run - < load/k6/scenarios/order-book-query.js
```

### C. docker-compose profile 활성

`infrastructure/docker-compose.yml` 에 추가된 `k6` 서비스 (profile=`load`):

```bash
docker compose -f infrastructure/docker-compose.yml --profile load up k6
```

## 통합 환경 기동

본 앱이 먼저 떠 있어야 한다. 두 가지 길:

### 1) 단독 bootRun (가장 가볍게)

```bash
./gradlew :market-bootstrap:bootRun
# BASE_URL=http://localhost:8080 으로 시나리오 실행
```

### 2) 통합 compose (PG / Saga / 외부 PG wiremock 까지 포함)

```bash
docker compose -p resell-integration \
    -f infrastructure/docker-compose.integration.yml up -d --build
./scripts/integration-demo.sh   # 시드 데이터 + 헬스 확인
# BASE_URL=http://localhost:8081 (기본값) — 통합 compose 가 8081 로 노출
```

## 시나리오별 실행

### 1) listing-create — ASK 등록

```bash
k6 run load/k6/scenarios/listing-create.js
```

| metric | 기준 |
|---|---|
| `http_req_duration` p95 | < 200ms |
| `http_req_failed` | < 1% |
| `listing_matched_count` | 일부 매칭 (가격이 BID 풀과 겹치면) |
| `listing_unmatched_carry` | 다수 (호가창 적재) |

ASK 가격을 BID 분포 상한보다 살짝 위로 잡아 매칭률을 약 30% 로 낮춘다 — 호가창 적재가
주된 path 가 되도록 한다 (order-book-query 시나리오와 동시에 돌렸을 때 빈 호가창이
되지 않도록).

### 2) order-match — BID 등록 + 매칭 트리거

```bash
k6 run load/k6/scenarios/order-match.js
```

| metric | 기준 |
|---|---|
| `http_req_duration` p95 | < 500ms |
| `http_req_failed` | < 2% |
| `bid_matched_count` | ramping VU 가 늘수록 증가 |

`/api/v1/bids` 는 BID INSERT + 가장 낮은 ASK 조회 (`FOR UPDATE`) + 매칭 → Trade INSERT
+ Outbox INSERT 를 한 트랜잭션 안에서 수행한다. 같은 SKU 에 다중 BID 가 동시에 들어오면
advisory lock 으로 직렬화되어 p95 가 단계적으로 올라간다. ramping VU 0 → 200 으로
그 지점이 어디인지 본다.

### 3) order-book-query — 호가창 조회 (read-heavy)

```bash
k6 run load/k6/scenarios/order-book-query.js
```

| metric | 기준 |
|---|---|
| `http_req_duration` p95 | < 50ms (캐시 hit) |
| `http_req_duration` p99 | < 150ms (miss + DB fallback) |
| `http_req_failed` | < 1% |

500 req/s constant — 매칭 부하와 *함께* 돌렸을 때 cache invalidation (호가가 바뀌면
Redis pub/sub 으로 다른 instance 의 캐시도 무효화) race 에서도 p95 가 흔들리지 않는지 본다.

### 4) trade-history — cursor pagination

```bash
k6 run load/k6/scenarios/trade-history.js
```

| metric | 기준 |
|---|---|
| `http_req_duration` p95 | < 100ms |
| `http_req_duration` p99 | < 250ms |
| `http_req_failed` | < 1% |
| `history_pages_fetched` | iter 마다 1~2 — nextCursor 가 있으면 따라감 |

첫 페이지 + nextCursor 가 있으면 한 번 더 따라가는 2-step 패턴 — cursor 토큰 디코딩 +
WHERE `created_at < ?` 의 latency 를 본다.

### 5) concurrent-match — 동시 매칭 invariant 검증

```bash
k6 run load/k6/scenarios/concurrent-match.js
```

| metric | 의미 |
|---|---|
| `match_invariant_violation` count | **0 이어야 함** — 같은 Trade ID 가 두 호가 응답에 중복 매핑되면 위반 |
| `cm_matched_responses` count | client 가 본 매칭 응답 수 |
| `http_req_failed` rate | < 5% (advisory lock 대기 timeout 일부 허용) |

단일 SKU 에 30 VU 가 BID/ASK 를 반반 쏟아붓는다 — race 조건 극대화. ADR 의 핵심
invariant **"한 SKU 의 매칭은 advisory lock + FOR UPDATE SKIP LOCKED 로 직렬화되어
matched 응답 수 == DB Trade INSERT 수"** 가 정상이면 같은 `matchedTradeId` 가 두 응답에
나올 수 없다.

한계: client 단에선 DB 직접 조회를 안 한다. 진짜 DB-level 정합성은 `e2e-tests` 의
"Postgres 위 동시 매칭 race" 테스트가 담당한다. 본 시나리오는 운영 환경 fail-fast
회귀 감지 용도다.

## 한 번에 실행

```bash
./scripts/run-load.sh
```

listing-create → order-match → order-book-query → trade-history → concurrent-match
순으로 단계 실행. 결과는 `build/k6-reports/{scenario}.json` 에 떨군다.

## 환경변수

| key | 기본 | 설명 |
|---|---|---|
| `BASE_URL` | `http://localhost:8081` | HTTP base — 단독 bootRun 이면 `:8080` |
| `K6_TOKEN` | (빈 값) | JWT on 일 때만 의미 — auth-stub 발급 토큰 |
| `K6_SKUS` | 5개 기본 SKU | round-robin 할 SKU CSV |
| `CONCURRENT_MATCH_SKU` | `...cafe00000099` | concurrent-match 가 race 를 집중시킬 단일 SKU |

## k6 metric 해석

| metric | 의미 |
|---|---|
| `vus` / `vus_max` | 현재 / 최대 VU |
| `iter_duration` | 한 default 함수 실행 시간 — sleep 포함 |
| `http_req_duration` | HTTP 응답 소요 (connect + TLS + waiting 합) |
| `http_req_waiting` | TTFB — server-side latency 의 근사 |
| `http_req_failed` | non-2xx 비율 |
| `data_received` / `data_sent` | byte 카운터 — 네트워크 IO 추세 |

### p95 / p99 보는 법

- **p95** 는 변동성 신호 (95 백분위) — 일상 SLO 의 기준.
- **p99** 는 꼬리 신호 — GC, advisory lock 경합, 외부 PG 호출 지연 등 드문 이벤트.
- p95 → p99 격차가 크면 reliability tail 이 두꺼운 것. 매칭 엔진의 경우 advisory lock
  대기시간 분포가 long-tail 일 가능성이 가장 크다 — `pg_locks` view 와 함께 본다.

### 매칭 엔진 특유 측정 항목

매칭 엔진은 일반적인 read/write throughput 외에 invariant 가 중요하다.

| 항목 | 어디서 보나 |
|---|---|
| **matched_count vs unmatched_carry** | listing-create / order-match 의 custom counter — 가격 분포가 의도대로 동작하는지 |
| **DB advisory lock contention** | `pg_locks` view (`locktype='advisory'`) 의 동시 활성 락 수 + `pg_stat_activity` 의 wait_event=`Lock` 비율 |
| **`pg_advisory_xact_lock` 대기 분포** | Spring AOP / log 에 lock 획득 시점을 micrometer Timer 로 두면 percentile 측정 가능 |
| **Outbox 적재 vs Kafka 발행 lag** | `outbox` 테이블의 `unpublished` 행 수 — OutboxRelay 가 부하 중에도 backlog 를 늘리지 않는지 |
| **Saga 다음 단계 지연** | Trade `status=MATCHED` → `PAYMENT_AUTHORIZED` 까지의 시간 — saga consumer 가 부하 중에 막히지 않는지 |
| **`match_invariant_violation`** | concurrent-match 의 client-side 검증 — 0 아니면 즉시 알람 |

### 시나리오별 부하 모델

| 시나리오 | executor | 모델 |
|---|---|---|
| listing-create | constant-arrival-rate | 100 req/s, 60s |
| order-match | ramping-vus | 0 → 50 → 100 → 200 VU, 100s |
| order-book-query | constant-arrival-rate | 500 req/s, 60s |
| trade-history | constant-arrival-rate | 200 req/s, 60s |
| concurrent-match | ramping-vus | 0 → 30 VU, 45s — race 극대화 |

`constant-arrival-rate` 는 RPS 기준 (read-heavy), `ramping-vus` 는 concurrency 기준
(매칭 엔진의 advisory lock 경합 측정) 에 적합하다.

## 결과 예시 (참고 — 환경마다 다름)

m1 max + docker-compose 통합 (1 instance, 4 cpu, 4G heap) 기준:

```
listing-create
  http_req_duration............. avg=35ms    p(95)=120ms  p(99)=240ms
  http_req_failed............... 0.0%
  listing_matched_count......... 1700        (총 6000 중 ~28%)
  listing_unmatched_carry....... 4300

order-match (ramping 0→200 VU)
  http_req_duration............. avg=95ms    p(95)=380ms  p(99)=820ms
  http_req_failed............... 0.4%        (advisory lock timeout 일부)
  bid_matched_count............. 9800

order-book-query
  http_req_duration............. avg=12ms    p(95)=38ms   p(99)=110ms
  http_req_failed............... 0.0%
  iterations.................... 30000       rate=500/s

trade-history
  http_req_duration............. avg=32ms    p(95)=82ms   p(99)=180ms
  http_req_failed............... 0.0%
  history_pages_fetched......... 18000       (1.5 page / iter 평균)

concurrent-match
  cm_matched_responses.......... 450
  match_invariant_violation..... 0           ← 핵심 invariant
  http_req_failed............... 1.2%
```

`match_invariant_violation` 이 1 이상이면 advisory lock 또는 SKIP LOCKED 로직이 깨진
신호 — `application/matching/` 의 동시성 검증 + e2e-tests 의 race 테스트를 다시 본다.

## Prometheus remote-write 연동 (commerce-ops 통합 대시보드)

5 시나리오 결과를 `commerce-ops` 의 Prometheus 로 흘려보내 한 Grafana 대시보드에서
client load + server actuator 를 같이 보고 싶을 때:

```bash
docker compose -f /path/to/commerce-ops/infra/docker-compose.yml up -d prometheus grafana

export K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write
./scripts/run-load.sh
```

`run-load.sh` 가 각 시나리오에 `service=resell-orderbook` / `scenario=<name>` tag 를
자동 부여한다. Grafana → **Portfolio Load (k6 + actuator)** 대시보드 (uid
`portfolio-load`) 에서 service 변수를 `resell-orderbook` 으로 선택. 10번 패널
"matched_count" 와 매칭 엔진 advisory lock 대기 분포 (Micrometer Timer) 가 같은
시간축에 잡혀 p99 spike 가 lock 대기 때문인지 GC 때문인지 한 화면에서 구분된다.
필요 k6 버전 **0.42+** (experimental-prometheus-rw output).

## 더 나아가려면

- 더 큰 부하는 k6 cloud / k6 distributed mode — 본 시나리오는 single-node 200 VU 선에서
  운용한다.
