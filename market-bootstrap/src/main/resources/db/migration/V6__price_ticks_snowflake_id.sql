-- price_ticks.id 를 UUID → BIGINT (Snowflake) 로 전환 (ADR-0018).
--
-- 왜?
--   1) UUID 는 무작위 → 인덱스 page write amplification + 시간 순 정렬 불가
--   2) Snowflake long 은 시간 순 단조 증가 → cursor pagination ("WHERE id > ? LIMIT N") 가능
--      + 인덱스 fan-out 단순 (timestamp 순 append) → 디스크 캐시 효율 ↑
--   3) 16 byte → 8 byte: 인덱스 절반 크기.
--
-- price_ticks 는 append-only 시계열 (PriceTick) 이고 *외부 FK 가 없다* (UNIQUE(trade_id) 만으로
-- 충분). 따라서 PK 타입 변경의 영향이 이 테이블 안에서 끝난다.
--
-- 마이그레이션 전략:
--   * 기존 UUID id 행은 한정판 가격 차트의 raw 데이터지만, 같은 정보가 trades 테이블에 모두 있으므로
--     필요 시 재생성 가능. 따라서 단순화를 위해 *컬럼 타입을 통째로 swap* 하고 인덱스를 다시 만든다.
--   * Postgres 는 USING 절 없이 UUID → BIGINT 캐스트가 안 되므로, 안전하게 테이블을 비우고
--     컬럼을 ALTER 하는 방식 (price_ticks 는 derivable — trades 에서 재생성 가능).
--   * dev (H2) 는 매번 빈 테이블이라 무시.

-- 1) 기존 PK 제거 + UUID 컬럼 삭제 + BIGINT id 추가.
TRUNCATE TABLE price_ticks;
ALTER TABLE price_ticks DROP CONSTRAINT IF EXISTS price_ticks_pkey;
ALTER TABLE price_ticks DROP COLUMN id;
ALTER TABLE price_ticks ADD COLUMN id BIGINT NOT NULL;
ALTER TABLE price_ticks ADD CONSTRAINT price_ticks_pkey PRIMARY KEY (id);

-- 2) Snowflake 시간 순 cursor pagination 인덱스 — 차트 무한 스크롤의 hot path.
CREATE INDEX IF NOT EXISTS idx_price_tick_sku_id
    ON price_ticks (sku_id, id);

-- 3) 모니터링 / OHLC 재집계 등 시간 구간 풀스캔용 인덱스는 V3 의 idx_price_tick_time 그대로 유효.
