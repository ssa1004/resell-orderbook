-- OHLC 캔들스틱 (Open/High/Low/Close = 시작가/최고가/최저가/종가를 묶은 한 봉) 사전 집계.
-- 1분/5분/1시간/1일 단위. raw price_ticks 를 시간 구간별로 묶어 차트 응답을 가볍게 만든다 —
-- 24시간 1분 차트라면 1440개 봉만 SELECT 하면 된다.

CREATE TABLE ohlc_candles (
    id              UUID            PRIMARY KEY,
    sku_id          UUID            NOT NULL,
    period          VARCHAR(16)     NOT NULL,        -- ONE_MIN / FIVE_MIN / ONE_HOUR / ONE_DAY
    bucket_start    TIMESTAMP       NOT NULL,        -- 봉의 시작 시각 (period 단위로 정렬됨, 예: 14:23 → 14:00)
    open_amount     DECIMAL(18, 0)  NOT NULL,
    high_amount     DECIMAL(18, 0)  NOT NULL,
    low_amount      DECIMAL(18, 0)  NOT NULL,
    close_amount    DECIMAL(18, 0)  NOT NULL,
    currency        VARCHAR(3)      NOT NULL,
    volume          BIGINT          NOT NULL,
    trade_count     BIGINT          NOT NULL,
    -- 봉이 닫히면 정확히 한 번만 INSERT — 같은 (sku, period, bucket) 이 두 번 들어오면 거절.
    -- 배치를 다시 돌리거나 인스턴스가 여러 대여서 동시에 실행돼도 결과 동일 (멱등 + 동시 실행 안전).
    CONSTRAINT uq_ohlc_sku_period_bucket UNIQUE (sku_id, period, bucket_start),
    CONSTRAINT chk_ohlc_volume_nonneg     CHECK (volume >= 0),
    CONSTRAINT chk_ohlc_count_nonneg      CHECK (trade_count >= 0),
    CONSTRAINT chk_ohlc_low_le_high       CHECK (low_amount <= high_amount)
);

-- 차트 쿼리 — "이 SKU 의 1시간 봉 최근 24개" 같은 패턴이 가장 빈번
CREATE INDEX idx_ohlc_sku_period_time
    ON ohlc_candles (sku_id, period, bucket_start DESC);
