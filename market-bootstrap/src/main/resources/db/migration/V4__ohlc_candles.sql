-- OHLC 캔들 사전 집계 (1분/5분/1시간/1일).
-- raw price_ticks 를 그룹화해 차트 응답을 빠르게 — 24h 1분 차트면 1440개 점만 SELECT.

CREATE TABLE ohlc_candles (
    id              UUID            PRIMARY KEY,
    sku_id          UUID            NOT NULL,
    period          VARCHAR(16)     NOT NULL,        -- ONE_MIN / FIVE_MIN / ONE_HOUR / ONE_DAY
    bucket_start    TIMESTAMP       NOT NULL,        -- bucket 의 시작 시각 (period 단위로 정렬)
    open_amount     DECIMAL(18, 0)  NOT NULL,
    high_amount     DECIMAL(18, 0)  NOT NULL,
    low_amount      DECIMAL(18, 0)  NOT NULL,
    close_amount    DECIMAL(18, 0)  NOT NULL,
    currency        VARCHAR(3)      NOT NULL,
    volume          BIGINT          NOT NULL,
    trade_count     BIGINT          NOT NULL,
    -- bucket 닫히면 한 번만 INSERT — 같은 (sku, period, bucket) 두 번 들어오면 거절.
    -- 배치 멱등성 + 동시 실행 안전.
    CONSTRAINT uq_ohlc_sku_period_bucket UNIQUE (sku_id, period, bucket_start),
    CONSTRAINT chk_ohlc_volume_nonneg     CHECK (volume >= 0),
    CONSTRAINT chk_ohlc_count_nonneg      CHECK (trade_count >= 0),
    CONSTRAINT chk_ohlc_low_le_high       CHECK (low_amount <= high_amount)
);

-- 차트 query — "이 SKU 의 1시간 candle 최근 24개"
CREATE INDEX idx_ohlc_sku_period_time
    ON ohlc_candles (sku_id, period, bucket_start DESC);
