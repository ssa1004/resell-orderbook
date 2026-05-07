# ADR-0016: OHLC 캔들스틱 사전 집계 (Market Data 후속)

## 상태
적용

## 배경

ADR-0015 의 *raw PriceTick* 만으로 차트를 그리려면:
- 24시간 차트: tick 수가 분당 수십~수백건이라면 *수만 점*
- 1주 차트: *수십만 점*

브라우저 / mobile 이 받기 너무 무거움. 거의 모든 거래소 (NASDAQ / Binance / Upbit / Kream)
가 같은 방식으로 해결: **사전 집계된 OHLC 캔들** (1분/5분/1시간/1일).

## 결정

### 도메인 모델

```java
record OhlcCandle(
    UUID id, SkuId skuId, OhlcPeriod period, Instant bucketStart,
    Money open,        // bucket 안 첫 체결가
    Money high,        // 최대
    Money low,         // 최소
    Money close,       // 마지막 체결가
    long volume,       // 거래 건수 (한정판은 수량 = 1 이라 == tradeCount)
    long tradeCount
) {
  // invariant: low ≤ open/close ≤ high. record 생성자에서 검증.
}

enum OhlcPeriod {
  ONE_MIN(1m), FIVE_MIN(5m), ONE_HOUR(1h), ONE_DAY(1d);
  bucketStart(Instant)  // alignment
  bucketEnd(Instant)
  duration()
}
```

### Bucket 전략 (alignment)

각 period 의 bucket 은 *시간 정렬* (alignment):
- `ONE_MIN` → 분 단위 (`14:23:45 → 14:23:00`)
- `FIVE_MIN` → 5분 단위 (`14:23 → 14:20`)
- `ONE_HOUR` → 시간 정각
- `ONE_DAY` → UTC 자정

같은 bucket 의 tick 은 정확히 같은 candle 에 들어가도록 *결정적* — 시간대 / dst 영향 없음 (UTC 기준).

### Append-only + UNIQUE constraint

```sql
UNIQUE (sku_id, period, bucket_start)
```

bucket 이 닫힌 후 (시간 지나서 더 이상 새 tick 안 들어옴) 정확히 1번 INSERT. raw tick 도 append-only
이라 OHLC 도 영구 불변. 같은 bucket 에 두 번째 INSERT 시도 → DB constraint 가 거절 → 배치 멱등성.

### Aggregation batch — 직전 bucket 만 닫음

```java
@Scheduled(cron = "5 * * * * *")  // 매 분 5초
runOhlcOneMin() → ohlcService.closePreviousBucket(ONE_MIN, now)
```

각 cron 은 *직전 bucket* (시간 지나서 닫힌 것) 만 처리. 진행 중 bucket 을 집계하면 batch 후에
들어온 tick 이 그 candle 에 누락 (수정 안 됨 — append-only). 1분 batch 라면:
```
12:00:00 ─ tick 들 ─ 12:00:59
12:01:05 → batch 가 [12:00, 12:01) bucket 닫음
12:01:00 ─ tick 들 ─ 12:01:59
12:02:05 → batch 가 [12:01, 12:02) bucket 닫음
```

5초 offset (`5 * * * * *`) 은 *직전 분 마지막 tick 이 commit 되고 visible 한 후* 집계 보장.

### 4개 cron — period 별 다른 빈도

| Period | Cron | lockAtMostFor |
|---|---|---|
| ONE_MIN | `5 * * * * *` (매 분 5초) | PT2M |
| FIVE_MIN | `10 */5 * * * *` (5분마다 10초) | PT4M |
| ONE_HOUR | `30 0 * * * *` (매 시간 30초) | PT30M |
| ONE_DAY | `0 1 0 * * *` (UTC 자정 + 1분) | PT1H |

각각 직전 bucket 만 처리. lockAtMostFor 는 다음 batch 가 cover 가능한 시간으로 짧게.

### 멱등성 (DataIntegrityViolationException 처리)

같은 SKU × period × bucket 이 이미 있으면 unique constraint 위반 →
`DataIntegrityViolationException`. 보통 scheduler 가 두 번 돌았다는 뜻 (multi-instance 또는
재실행) — 조용히 skip + log.warn. 한 SKU 의 실패가 다른 SKU 막지 않음 (`try/catch`).

### 한정판 sneaker 의 volume = tradeCount

거래 단위가 1 (한 켤레 = 한 점) 이라 `volume == tradeCount`. 다른 도메인 (가변 수량) 으로
확장 시 `OhlcCandle.volumeFromTicks(...)` 한 메서드만 바꾸면 됨.

### REST

```
GET /api/v1/market/ohlc/{skuId}?period=ONE_HOUR&from=...&to=...&limit=24
→ TradingView / Highcharts 가 그대로 받아 그릴 수 있는 표준 형태
```

응답에 `currency` 포함 — frontend 가 통화 표시 (₩180,000 등) 하는데 사용.

## 대안 검토

- **요청 시점 on-demand 집계** — DB 에 raw tick 만 저장, candle 은 매 요청마다 GROUP BY 로 계산.
  거부. 차트 query 가 조금만 빈번해도 DB 부담. 사전 집계로 read latency 일정.
- **TimescaleDB 의 continuous aggregate** — 시계열 DB 의 자동 사전 집계. 강력하지만
  PG → TimescaleDB 마이그레이션 비용. 데이터 양이 PG 인덱스 한계 도달 시 도입.
- **Apache Druid / ClickHouse** — OLAP DB. 거래소 규모 (수억 tick/일) 면 적합. 본 프로젝트엔 과함.
- **Streaming (Kafka Streams + state store)** — 실시간 candle. 복잡도 큼. 1분 lag 면 사용자 차트는 충분.
- **Trade 이벤트 listener 가 in-memory 누적** — JVM 죽으면 진행 분 손실 + 메모리 폭증. 거부.

## 결과

- 차트 응답 가벼움 — 24h 1분 candle = 1440 행, 1주 1시간 candle = 168 행
- 인덱스 1개 (`sku_id, period, bucket_start DESC`) 가 모든 차트 query cover
- Append-only + UNIQUE 로 멱등성 + 정합 — 배치 재실행 안전
- (단점) ~1분 lag — 진짜 실시간 (틱 단위) 차트는 추가 streaming 필요
- (단점) period 별 row 수 누적 — ONE_MIN 1년 = SKU 1개당 525,600 행. partition (월 단위) 검토.

## 후속 후보

- WebSocket / STOMP 로 *closed candle* push (`market.ohlc.{period}`)
- 진행 중 bucket 의 partial candle (in-memory aggregator) — 진짜 실시간 차트
- TimescaleDB continuous aggregate — 데이터 폭증 시
- Volume Weighted Average Price (VWAP) / 이동평균 (SMA, EMA) — 보조 지표
- Candle archive — 1년 지난 ONE_MIN candle → cold storage (S3 Parquet)
