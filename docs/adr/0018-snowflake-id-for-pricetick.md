# ADR-0018: Snowflake ID — PriceTick 의 시간 정렬 가능한 64bit 식별자

## 상태
적용

## 배경

`PriceTick` (체결 1건 시점의 가격) 은 매칭이 일어날 때마다 1행씩 INSERT 만 되는 *append-only*
시계열 데이터다. 가격 차트, 24시간 통계, OHLC 캔들스틱이 모두 이 테이블에서 파생된다.
따라서 자주 일어나는 read query 두 가지가 있다:

1. *최근 N건* (last trade, 차트 right-edge): `ORDER BY occurred_at DESC LIMIT N`
2. *cursor pagination* (무한 스크롤, 실시간 차트 follow-up): "이전 페이지의 마지막 다음부터 N건"

기존 PK 는 `UUID` 였다. 다음과 같은 약점:

- *무작위* — 인덱스 page 가 어디로 갈지 모르므로 매 INSERT 마다 random page 에 흩뿌려 쓴다
  (write amplification). buffer pool / OS page cache 에 hit 률이 떨어진다.
- *시간 비교 불가* — UUID 두 개를 비교해 시간 순서를 알 수 없다. cursor pagination 을 하려면
  `(occurred_at, id)` 튜플 비교가 필요한데 등호 처리 / NULL 처리가 까다롭다.
- *16 byte* — 8 byte (long) 의 두 배. 인덱스 메모리도 두 배.

본 ADR 은 PriceTick 의 ID 를 **Snowflake** 형식의 64bit `long` 으로 바꾼다 (Twitter 가
2010년 공개한 분산 ID 발급 알고리즘 — 시간 정렬 가능한 long ID 가 필요한 시계열/메시지
시스템에서 표준 패턴으로 자리 잡았다).

## 결정

### 비트 배치

```
| 1 bit | 41 bit       | 10 bit         | 12 bit       |
| 부호=0 | timestamp(ms)| machine id     | sequence     |
```

- 41bit timestamp: epoch (`2026-01-01T00:00:00Z`) 이후 ~69년 표현 가능
- 10bit machine id: 인스턴스(=K8s pod) 별 고유 — 최대 1024 인스턴스
- 12bit sequence: 같은 ms 안의 1씩 증가 — 인스턴스 한 대 당 1ms 에 4096 ID

이 배치 덕에 `id` 만 long 으로 비교해도 *시간 순* (생성 시각이 같으면 인스턴스 + 순서 순). UUID
의 결정적 약점이 모두 해결된다.

### Machine id 결정

생성기는 인스턴스 시작 시점에 machine id 를 주입받는다. 우선순위:

1. `MACHINE_ID` 환경변수 (운영 — K8s StatefulSet ordinal 등 명시)
2. `market.snowflake.machine-id` 프로퍼티
3. hostname 의 hash mod 1024 (자동 — pod 이름 기반)
4. 위 셋 다 실패 시 0 (단일 인스턴스 dev)

운영 권장: StatefulSet 의 안정 hostname (pod-0, pod-1, ...) → hostname hash 자동도 충돌 가능성
낮음 (1024 슬롯 대비 보통 인스턴스 수십 대). 더 엄격한 보장이 필요하면 init-container 에서
ZooKeeper / Redis 의 분산 카운터를 받아 env 로 주입.

### Clock backward 방어

NTP 동기화로 시계가 살짝 뒤로 가는 일은 흔하다. 만약 그 사이에 새 ID 를 발급하면 *과거 ID 와
충돌* 위험. 두 단계 방어:

1. *작은 backward (≤10초)*: `lastTimestamp` 를 그대로 유지하면서 sequence 만 증가시켜
   단조 증가를 유지. 시계가 곧 따라잡으면 자연 복귀.
2. *큰 backward (>10초)*: 인프라 문제 (NTP 망가짐 등) — 즉시 `IllegalStateException`
   으로 빠른 실패. silent corruption 보다 fail-fast 가 낫다.

### DB 마이그레이션 (V6)

`price_ticks.id` 를 `UUID PRIMARY KEY` → `BIGINT PRIMARY KEY` 로 swap.

```sql
TRUNCATE TABLE price_ticks;                    -- 같은 정보가 trades 에 있어 derivable
ALTER TABLE price_ticks DROP CONSTRAINT price_ticks_pkey;
ALTER TABLE price_ticks DROP COLUMN id;
ALTER TABLE price_ticks ADD COLUMN id BIGINT NOT NULL;
ALTER TABLE price_ticks ADD CONSTRAINT price_ticks_pkey PRIMARY KEY (id);
CREATE INDEX idx_price_tick_sku_id ON price_ticks (sku_id, id);
```

기존 행을 *삭제* 하는 destructive migration 이지만 본 시스템에서 받아들일 수 있는 이유:

- price_ticks 는 `trades` 테이블에서 *재생성 가능* — 거래 1건 당 정확히 tick 1건이라
  운영 SQL 한 줄로 backfill 가능
- 시스템이 아직 운영 데이터 적재 전 단계 (이 시점까지의 ADR 들이 전부 demo 수준)

운영 데이터가 큰 시스템이라면 *추가 컬럼* 전략 (id_seq BIGINT 추가 → backfill →
점진 cutover → 옛 PK 제거) 이 안전.

### Cursor pagination 새 인덱스

```
CREATE INDEX idx_price_tick_sku_id ON price_ticks (sku_id, id);
```

이 인덱스는 `WHERE sku_id = ? AND id > ? ORDER BY id ASC LIMIT N` 형태의 차트 무한 스크롤에 직격.
기존 `idx_price_tick_sku_time (sku_id, occurred_at DESC)` 도 24h 통계 등의 시간 구간 쿼리에 그대로
유효 — 둘이 역할이 다르므로 공존.

## 대안 검토

- **UUID v7** (timestamp-prefixed UUID) — 시간 순 정렬은 가능하지만 여전히 16 byte. snowflake
  대비 인덱스 크기 2배. 주된 장점은 *전역 유일성* 인데, 본 시스템은 단일 클러스터라 그게 큰 이점이
  아니다.
- **DB sequence** (`BIGSERIAL`) — Postgres native 이지만 1) 시간 정보 없음 (모니터링 / 디버깅에서
  ID 만 보고 발급 시각을 알 수 없음) 2) 분산 환경에서는 sequence 의 RTT 가 INSERT 마다 일어나
  hot path 부담. snowflake 는 in-process 로 ns 단위.
- **timestamp+random** — 충돌 확률 낮지만 0 이 아니고, 같은 ms 안의 ordering 이 모호.
- **UUID 유지 + occurred_at 으로 정렬** — 현재 패턴. 인덱스 크기 / write amplification / cursor
  pagination 의 단점 미해결.

## 결과

- (장) 16 byte → 8 byte: 인덱스 절반 크기 / buffer pool 더 잘 들어감
- (장) 시간 순 정렬이 ID 비교만으로 가능 → cursor pagination 단순
- (장) 인덱스 page 가 timestamp 순 append → write amplification 해소
- (장) `id` 만으로 발급 시각 + 발급 인스턴스 디코딩 → 모니터링 / 로그 분석에 유용
- (단) 단일 인스턴스 PK 형태의 DB sequence 보다 코드 한 단계 더 — generator Bean / machine-id
  관리 필요
- (단) machine id 충돌 시 *조용한 ID 충돌* 위험 — 운영에서 명시 주입 권장
- (단) 41bit timestamp 한계 — 2026 epoch 기준 ~2095 까지. 그 이전에 차세대 형식 검토 필요

## 후속 후보

- `OrderEvent` 같은 audit 이벤트에도 동일 패턴 적용 — 같은 식별자/정렬 이점.
- `Trade.id` 도 snowflake 화 검토. 단 trade 는 외부 노출 ID (영수증, 조회 URL) 라 hardcoded
  UUID 인용이 많을 수 있어 risky — 도메인 코드 영향이 큰 swap 이라 신중.
- machine id 의 ZooKeeper / Redis 분산 카운터 자동 발급기.
