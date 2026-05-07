-- 시세 시계열 (체결 틱) — 매칭 1건 당 row 1개. append-only (한번 INSERT 후 절대 UPDATE/DELETE
-- 하지 않음). 가격 차트, 24h 통계, OHLC 캔들스틱이 모두 이 테이블에서 만들어진다.

CREATE TABLE price_ticks (
    id              UUID            PRIMARY KEY,
    trade_id        UUID            NOT NULL,
    sku_id          UUID            NOT NULL,
    price_amount    DECIMAL(18, 0)  NOT NULL,    -- 통화는 KRW 라 소수점 자릿수 0
    currency        VARCHAR(3)      NOT NULL,
    occurred_at     TIMESTAMP       NOT NULL,
    CONSTRAINT chk_price_tick_positive CHECK (price_amount > 0),
    -- 같은 거래(trade)에는 tick 1건만 — 매칭 코드가 실수로 두 번 호출돼도 두 번째 INSERT 가
    -- DB 자체에서 거절되어 정합성이 보호된다.
    CONSTRAINT uq_price_tick_trade UNIQUE (trade_id)
);

-- 차트 쿼리 (특정 SKU 의 시간 구간) — 가장 자주 쓰임
CREATE INDEX idx_price_tick_sku_time
    ON price_ticks (sku_id, occurred_at DESC);

-- 운영/정합성 검사용 — 시간 구간 전체 스캔 (모니터링, OHLC 재집계 등)
CREATE INDEX idx_price_tick_time
    ON price_ticks (occurred_at DESC);
