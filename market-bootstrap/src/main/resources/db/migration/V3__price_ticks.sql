-- 시세 시계열 (체결 틱) — 매칭 1건 = 1 row, append-only.
-- 차트 / 24h 통계 / 캔들스틱은 이 테이블에서 도출.

CREATE TABLE price_ticks (
    id              UUID            PRIMARY KEY,
    trade_id        UUID            NOT NULL,
    sku_id          UUID            NOT NULL,
    price_amount    DECIMAL(18, 0)  NOT NULL,    -- KRW 라 소수점 0
    currency        VARCHAR(3)      NOT NULL,
    occurred_at     TIMESTAMP       NOT NULL,
    CONSTRAINT chk_price_tick_positive CHECK (price_amount > 0),
    -- 같은 trade 의 tick 은 1건만 — 매칭 트랜잭션이 두 번 record 되어도 두 번째 INSERT 거절
    CONSTRAINT uq_price_tick_trade UNIQUE (trade_id)
);

-- 차트 query (특정 SKU 의 시간 구간) — 가장 자주 사용
CREATE INDEX idx_price_tick_sku_time
    ON price_ticks (sku_id, occurred_at DESC);

-- 운영 / 정합성 — 시간 구간 전체 스캔 (모니터링 / 재집계)
CREATE INDEX idx_price_tick_time
    ON price_ticks (occurred_at DESC);
