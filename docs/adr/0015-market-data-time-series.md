# ADR-0015: Market Data — 시세 시계열과 통계 read API

## 상태
적용

## 배경

한정판 리셀 시장에서 사용자/운영자가 화면에서 가장 자주 보는 정보:

- 이 SKU 의 *가장 최근 체결가* 와 *몇 분 전* 인지
- 호가창의 *best bid / best ask* 와 *spread*
- *24시간 거래량* 과 *최저/평균/최고가* — 변동성 / 추세 파악
- 가격 *차트* (시간 vs 가격)

매칭 (write side) 만으로는 이 정보가 즉시 나오지 않는다. 매번 계산하기엔 무겁고 (특히 차트
데이터), 호가창 (실시간 best) 과 거래 이력 (과거 체결) 이 다른 도메인이라 한 번의 조회로 모두
얻으려면 통합 read service 가 필요하다.

## 결정

### 두 read 모델

```
PriceTick      ─ 한 거래 = 1 row, append-only 시계열
                 (주식의 체결 데이터와 같은 형태)

MarketStats    ─ 화면 응답용 카드 (저장하지 않음, 요청 시 계산)
                 last trade + best bid/ask + 24h 통계를 한 응답에 묶음
```

### Write — 매칭과 같은 트랜잭션

매칭이 일어나는 4곳 (`PlaceListing`, `PlaceBid`, `BuyNow`, `SellNow`) 에서 Trade.save 직후
*같은 트랜잭션* 에 `priceTicks.save(PriceTick.from(t.id(), t.skuId(), t.price(), now))` 를
호출. 매칭과 시세 틱이 항상 같이 commit / 같이 rollback → 시세 이력에서 거래가 누락되지 않음.

이중 저장은 DB constraint `UNIQUE(trade_id)` 가 막는다 — 같은 trade 가 두 번 record 되면
두 번째 INSERT 실패 → 트랜잭션 rollback. 코드 레벨에서 한 번만 호출하면 OK.

### Read — Aggregation push-down

24h 통계 (`count`, `min`, `avg`, `max`) 는 SQL `GROUP BY` 로 한 번에 — application 메모리로
모든 tick 끌어와 계산하지 않음. tick 수가 늘어도 응답 시간 일정.

차트 데이터는 시간 역순 + limit — 클라이언트가 "최근 N건" 만 받음. 더 과거가 필요하면
`from / to` 좁혀 다시 호출.

### Index

```sql
INDEX (sku_id, occurred_at DESC)   -- 차트 query 가장 빈번
INDEX (occurred_at DESC)           -- 운영 모니터링 / 재집계
```

`(sku_id, occurred_at)` covering index 가 차트 + 24h 통계 query 모두 cover. 단일 SKU 에서
기간 슬라이스만 보므로 효율적.

### REST

```
GET /api/v1/market/stats/{skuId}                         → 시세 카드
GET /api/v1/market/ticks/{skuId}?from=...&to=...&limit=  → 차트 raw 데이터
```

## 대안 검토

- **TradeMatched 이벤트 컨슈머가 비동기로 PriceTick 저장** — 매칭 트랜잭션과 분리.
  거부. 시세가 *반드시* 매칭과 같이 잡혀야 함 (매칭 후 시세 누락은 운영 사고). 한 트랜잭션이
  훨씬 단순.
- **MarketStats 미리 계산해서 저장 (materialized view)** — 매분 batch 가 통계 갱신.
  거부. 24h 통계는 매번 계산해도 인덱스 위에서 빠르고, *실시간성* 이 중요. 트래픽 늘면 그때
  Redis 캐시 1분 TTL 만 추가하면 됨.
- **시계열 DB (TimescaleDB / InfluxDB) 도입** — 더 큰 규모 (초당 수만 tick) 에 적합.
  현재 한정판 리셀 트래픽 (시간당 수백~수천 거래) 에는 PG + 인덱스로 충분. 도입 시점은
  PG `EXPLAIN` 이 시간 잡아먹기 시작할 때.
- **OHLC 캔들스틱 사전 집계** — 1분/5분/1시간 단위로 미리 계산해 chart 응답 빠르게.
  현 단계에서는 raw tick 이면 충분 (frontend 가 그룹핑). 트래픽 증가하면 후속 ADR.

## 결과

- 화면 한 번의 호출로 사용자/운영자가 보고 싶은 정보 한 번에 응답
- 매칭과 시세 틱 원자성 — *시세에 거래 누락* 같은 데이터 정합 사고 회피
- write side (도메인) 는 단순 — `priceTicks.save(...)` 한 줄 추가만
- (단점) 4곳에서 같은 코드 반복 — 추후 EventPublisher decorator 또는
  `@TransactionalEventListener` 로 추출 가능 (지금은 명시적 호출이 *언제 record 되는지* 더 분명)
- (단점) read side 가 PG 부하를 그대로 받음 — 트래픽 늘면 read replica + 캐시 도입

## 후속 후보

- 1분/5분/1시간/1일 OHLC 사전 집계 + REST `/api/v1/market/ohlc/{skuId}?period=1H&count=24`
- WebSocket / STOMP 로 실시간 tick push (이미 호가창 push 채널 있음 — `market.tradematched` 컨슈머)
- 가격 변동 alert 도메인 (BudgetAlertRule 같은 패턴 — "X 가격 도달 시 알림")
- 시계열 DB (TimescaleDB) 도입 — 데이터 양이 PG + 인덱스의 한계 도달 시
