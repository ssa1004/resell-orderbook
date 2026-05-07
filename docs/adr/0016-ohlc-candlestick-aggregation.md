# ADR-0016: OHLC 캔들스틱 사전 집계 (Market Data 후속)

## 상태
적용

## 배경

ADR-0015 의 *raw PriceTick (개별 체결 단건)* 만으로 차트를 그리려면:
- 24시간 차트: tick 수가 분당 수십~수백건이라면 *수만 점*
- 1주 차트: *수십만 점*

브라우저 / 모바일이 받기 너무 무거움. 거의 모든 거래소 (NASDAQ / Binance / Upbit / Kream)
가 같은 방식으로 해결: **사전 집계된 OHLC 캔들** (Open/High/Low/Close = 시작가/최고가/최저가/
종가를 묶은 한 봉. 1분/5분/1시간/1일 단위로 묶음).

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

### Bucket 전략 (시간 정렬)

각 period 의 bucket (한 봉이 담는 시간 구간) 은 정해진 경계로 정렬 (alignment) 한다:
- `ONE_MIN` → 분 단위 (`14:23:45 → 14:23:00`)
- `FIVE_MIN` → 5분 단위 (`14:23 → 14:20`)
- `ONE_HOUR` → 시간 정각
- `ONE_DAY` → UTC 자정

같은 bucket 의 tick 은 결정적으로 (= 어느 환경에서 계산해도 같은 결과) 같은 candle 에 들어
간다. 시간대/일광절약시간(DST) 영향 없음 — UTC 기준.

### Append-only (추가 전용) + UNIQUE 제약

```sql
UNIQUE (sku_id, period, bucket_start)
```

bucket 이 닫힌 후 (시간이 지나 더 이상 새 tick 안 들어옴) 정확히 1번 INSERT. raw tick 도
한번 INSERT 후 절대 수정/삭제하지 않으니 (append-only) OHLC 도 영구 불변. 같은 bucket 에
두 번째 INSERT 가 시도되면 DB UNIQUE 제약이 거절 → 배치가 같은 시간대에 두 번 돌아도
결과 동일 (멱등).

### Aggregation batch — 직전 bucket 만 닫음

```java
@Scheduled(cron = "5 * * * * *")  // 매 분 5초
runOhlcOneMin() → ohlcService.closePreviousBucket(ONE_MIN, now)
```

각 cron 은 *직전 bucket* (시간이 지나 이미 닫힌 것) 만 처리. 진행 중인 bucket 을 집계하면
배치 이후에 들어온 tick 이 그 candle 에 누락된다 (수정 안 됨 — append-only). 1분 배치라면:
```
12:00:00 ─ tick 들 ─ 12:00:59
12:01:05 → batch 가 [12:00, 12:01) bucket 닫음
12:01:00 ─ tick 들 ─ 12:01:59
12:02:05 → batch 가 [12:01, 12:02) bucket 닫음
```

5초 오프셋 (`5 * * * * *`) 은 *직전 분 마지막 tick 이 커밋되고 다른 트랜잭션에서 보이는 (visible)
시점 이후* 에 집계가 도는 것을 보장한다.

### 4개 cron — period 별 다른 빈도

| Period | Cron | lockAtMostFor |
|---|---|---|
| ONE_MIN | `5 * * * * *` (매 분 5초) | PT2M |
| FIVE_MIN | `10 */5 * * * *` (5분마다 10초) | PT4M |
| ONE_HOUR | `30 0 * * * *` (매 시간 30초) | PT30M |
| ONE_DAY | `0 1 0 * * *` (UTC 자정 + 1분) | PT1H |

각각 직전 bucket 만 처리. lockAtMostFor (잠금 자동 해제 시간) 는 다음 배치가 따라잡을 수
있는 시간으로 짧게.

### 멱등성 (DataIntegrityViolationException 처리)

같은 SKU × period × bucket 이 이미 있으면 UNIQUE 제약 위반 →
`DataIntegrityViolationException`. 보통 스케줄러가 두 번 돌았다는 뜻 (인스턴스가 여러 대거나,
수동 재실행) — 조용히 건너뛰고 log.warn. 한 SKU 의 실패가 다른 SKU 의 처리를 막지 않음
(`try/catch`).

### 한정판 sneaker 의 volume = tradeCount

거래 단위가 1 (한 켤레 = 한 점) 이라 거래량(volume) == 거래 건수(tradeCount). 다른 도메인
(수량이 가변인 경우) 으로 확장 시 `OhlcCandle.volumeFromTicks(...)` 한 메서드만 바꾸면 됨.

### REST

```
GET /api/v1/market/ohlc/{skuId}?period=ONE_HOUR&from=...&to=...&limit=24
→ TradingView / Highcharts 가 그대로 받아 그릴 수 있는 표준 형태
```

응답에 `currency` 포함 — frontend 가 통화 표시 (₩180,000 등) 하는데 사용.

## 대안 검토

- **요청 시점에 즉석 집계 (on-demand)** — DB 에 raw tick 만 저장, candle 은 매 요청마다
  GROUP BY 로 계산. 거부. 차트 query 가 조금만 빈번해도 DB 부담. 사전 집계로 응답 시간 일정.
- **TimescaleDB 의 continuous aggregate (시계열 DB 가 자동으로 사전 집계해주는 기능)** —
  강력하지만 PG → TimescaleDB 마이그레이션 비용. 데이터 양이 PG 인덱스 한계 도달 시 도입.
- **Apache Druid / ClickHouse** — OLAP (대용량 분석용) DB. 거래소 규모 (일일 수억 tick) 면
  적합. 본 프로젝트엔 과함.
- **Streaming (Kafka Streams + state store)** — 실시간 candle. 복잡도 큼. 1분 정도 지연
  (lag) 이면 사용자 차트로는 충분.
- **Trade 이벤트 리스너가 메모리 안에서 누적** — JVM 이 죽으면 진행 분 손실 + 메모리 폭증.
  거부.

## 결과

- 차트 응답 가벼움 — 24h 1분 candle = 1440 행, 1주 1시간 candle = 168 행
- 인덱스 1개 (`sku_id, period, bucket_start DESC`) 가 모든 차트 쿼리 처리
- Append-only + UNIQUE 로 멱등성 + 정합 — 배치 재실행 안전
- (단점) 약 1분 지연 — 틱 단위 실시간 차트가 필요하면 별도 스트리밍 추가
- (단점) period 별 row 수 누적 — ONE_MIN 1년 = SKU 1개당 525,600 행. partition (테이블을
  월 단위 등으로 쪼개 저장) 검토.

## 후속 후보

- WebSocket / STOMP 로 *닫힌 candle* push (`market.ohlc.{period}`)
- 진행 중 bucket 의 부분 candle (메모리 안 aggregator 로 계산) — 틱 단위 실시간 차트
- TimescaleDB continuous aggregate — 데이터 폭증 시
- 거래량 가중 평균가 (VWAP, Volume Weighted Average Price) / 이동평균 (SMA: 단순 이동평균,
  EMA: 지수 이동평균) — 보조 지표
- 캔들 아카이브 — 1년 지난 ONE_MIN candle 은 콜드 스토리지 (자주 안 읽히는 데이터를 싸게
  보관하는 저장소, 예: S3 Parquet) 로 이동.
