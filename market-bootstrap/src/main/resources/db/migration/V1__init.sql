-- Resell Market Platform — V1 init schema
-- PostgreSQL + H2 (PostgreSQL mode) 호환

CREATE TABLE products (
    id              UUID PRIMARY KEY,
    brand           VARCHAR(80)  NOT NULL,
    model_name      VARCHAR(200) NOT NULL,
    style_code      VARCHAR(50),
    category        VARCHAR(30)  NOT NULL,
    release_date    TIMESTAMP,
    image_url       VARCHAR(500),
    created_at      TIMESTAMP    NOT NULL
);
CREATE INDEX ix_product_brand      ON products (brand);
CREATE INDEX ix_product_style_code ON products (style_code);

CREATE TABLE skus (
    id          UUID PRIMARY KEY,
    product_id  UUID         NOT NULL,
    size        VARCHAR(30)  NOT NULL,
    variant     VARCHAR(50),
    CONSTRAINT uk_sku_product_size_variant UNIQUE (product_id, size, variant)
);
CREATE INDEX ix_sku_product ON skus (product_id);

CREATE TABLE listings (
    id                UUID         PRIMARY KEY,
    sku_id            UUID         NOT NULL,
    seller_id         VARCHAR(64)  NOT NULL,
    ask_price         DECIMAL(19,0) NOT NULL,
    currency_code     VARCHAR(3)   NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    matched_trade_id  UUID,
    expires_at        TIMESTAMP    NOT NULL,
    created_at        TIMESTAMP    NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_listing_orderbook
    ON listings (sku_id, status, expires_at, ask_price, created_at);
CREATE INDEX ix_listing_seller ON listings (seller_id, status);

CREATE TABLE bids (
    id                UUID         PRIMARY KEY,
    sku_id            UUID         NOT NULL,
    buyer_id          VARCHAR(64)  NOT NULL,
    bid_price         DECIMAL(19,0) NOT NULL,
    currency_code     VARCHAR(3)   NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    matched_trade_id  UUID,
    expires_at        TIMESTAMP    NOT NULL,
    created_at        TIMESTAMP    NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_bid_orderbook
    ON bids (sku_id, status, expires_at, bid_price, created_at);
CREATE INDEX ix_bid_buyer ON bids (buyer_id, status);

CREATE TABLE trades (
    id                       UUID         PRIMARY KEY,
    sku_id                   UUID         NOT NULL,
    listing_id               UUID         NOT NULL,
    bid_id                   UUID         NOT NULL,
    seller_id                VARCHAR(64)  NOT NULL,
    buyer_id                 VARCHAR(64)  NOT NULL,
    price                    DECIMAL(19,0) NOT NULL,
    currency_code            VARCHAR(3)   NOT NULL,

    fee_seller_rate          DECIMAL(5,2) NOT NULL,
    fee_buyer_rate           DECIMAL(5,2) NOT NULL,
    fee_inspection           DECIMAL(19,0) NOT NULL,
    fee_shipping             DECIMAL(19,0) NOT NULL,
    fee_processing           DECIMAL(19,0) NOT NULL,
    fee_seller_commission    DECIMAL(19,0) NOT NULL,
    fee_buyer_commission     DECIMAL(19,0) NOT NULL,
    buyer_charge             DECIMAL(19,0) NOT NULL,
    seller_net               DECIMAL(19,0) NOT NULL,

    status                   VARCHAR(30)  NOT NULL,
    pg_payment_id            VARCHAR(100),
    inspection_fail_reason   VARCHAR(500),
    created_at               TIMESTAMP    NOT NULL,
    updated_at               TIMESTAMP    NOT NULL,
    version                  BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_trade_status_created ON trades (status, created_at);
CREATE INDEX ix_trade_seller         ON trades (seller_id);
CREATE INDEX ix_trade_buyer          ON trades (buyer_id);
CREATE INDEX ix_trade_listing        ON trades (listing_id);
CREATE INDEX ix_trade_bid            ON trades (bid_id);

CREATE TABLE inspection_requests (
    id              UUID         PRIMARY KEY,
    trade_id        UUID         NOT NULL,
    photo_urls      TEXT,
    requested_at    TIMESTAMP    NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    result_outcome  VARCHAR(10),
    result_reason   VARCHAR(500),
    result_note     VARCHAR(1000),
    inspector_id    VARCHAR(64),
    decided_at      TIMESTAMP,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_inspection_trade UNIQUE (trade_id)
);
CREATE INDEX ix_inspection_status ON inspection_requests (status, requested_at);

CREATE TABLE payouts (
    id                 UUID         PRIMARY KEY,
    trade_id           UUID         NOT NULL,
    seller_id          VARCHAR(64)  NOT NULL,
    trade_amount       DECIMAL(19,0) NOT NULL,
    seller_commission  DECIMAL(19,0) NOT NULL,
    processing_fee     DECIMAL(19,0) NOT NULL,
    net_amount         DECIMAL(19,0) NOT NULL,
    currency_code      VARCHAR(3)   NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    bank_transfer_id   VARCHAR(100),
    created_at         TIMESTAMP    NOT NULL,
    completed_at       TIMESTAMP,
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_payout_trade UNIQUE (trade_id)
);
CREATE INDEX ix_payout_seller_status ON payouts (seller_id, status);

CREATE TABLE refunds (
    id              UUID         PRIMARY KEY,
    trade_id        UUID         NOT NULL,
    buyer_id        VARCHAR(64)  NOT NULL,
    amount          DECIMAL(19,0) NOT NULL,
    currency_code   VARCHAR(3)   NOT NULL,
    reason          VARCHAR(500),
    status          VARCHAR(20)  NOT NULL,
    pg_refund_id    VARCHAR(100),
    requested_at    TIMESTAMP    NOT NULL,
    completed_at    TIMESTAMP,
    version         BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_refund_trade  ON refunds (trade_id);
CREATE INDEX ix_refund_status ON refunds (status, requested_at);

CREATE TABLE outbox (
    id              UUID         PRIMARY KEY,
    aggregate_id    VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         TEXT         NOT NULL,
    occurred_at     TIMESTAMP    NOT NULL,
    published_at    TIMESTAMP
);
CREATE INDEX ix_outbox_unpublished ON outbox (published_at, occurred_at);
