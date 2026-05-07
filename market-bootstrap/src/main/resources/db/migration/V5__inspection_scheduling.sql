-- 검수 슬롯 / 예약 시스템 — 셀러가 매물을 어느 센터에 언제 가져올지 예약.
-- Kream / StockX 같은 한정판 마켓의 핵심 운영 인프라.

-- ── 검수 센터 ─────────────────────────────────────────────────
CREATE TABLE inspection_centers (
    id                       UUID            PRIMARY KEY,
    name                     VARCHAR(128)    NOT NULL,
    address                  VARCHAR(512)    NOT NULL,
    -- 동시 검수 가능 인원 (= 검수원 수). 한 슬롯 안 최대 RESERVED+ARRIVED 수.
    parallel_capacity        INT             NOT NULL,
    -- 한 슬롯 길이 (분). 보통 60.
    slot_duration_minutes    BIGINT          NOT NULL,
    -- 예약 마감 (분). 슬롯 시작 X분 전까지만 예약 가능.
    booking_lead_time_minutes BIGINT         NOT NULL,
    created_at               TIMESTAMP       NOT NULL,
    CONSTRAINT chk_capacity_positive    CHECK (parallel_capacity > 0),
    CONSTRAINT chk_slot_duration_pos    CHECK (slot_duration_minutes > 0),
    CONSTRAINT chk_lead_time_nonneg     CHECK (booking_lead_time_minutes >= 0)
);


-- ── 검수 예약 ─────────────────────────────────────────────────
CREATE TABLE inspection_appointments (
    id              UUID            PRIMARY KEY,
    trade_id        UUID            NOT NULL,
    center_id       UUID            NOT NULL,
    slot_start      TIMESTAMP       NOT NULL,
    slot_end        TIMESTAMP       NOT NULL,
    status          VARCHAR(16)     NOT NULL,    -- RESERVED/ARRIVED/COMPLETED/REJECTED/CANCELLED/NO_SHOW
    booked_at       TIMESTAMP       NOT NULL,
    arrived_at      TIMESTAMP,
    completed_at    TIMESTAMP,
    version         BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_appt_center FOREIGN KEY (center_id) REFERENCES inspection_centers(id),
    CONSTRAINT chk_appt_slot_order CHECK (slot_end > slot_start)
);

-- *핵심 인덱스* — 한 (center, slot_start) 의 활성 (RESERVED+ARRIVED) 카운트 빠르게.
-- BookAppointmentService 가 advisory lock 잡고 이 query 로 capacity 체크.
CREATE INDEX idx_appt_center_slot_status
    ON inspection_appointments (center_id, slot_start, status);

-- 한 trade 의 활성 예약 조회 — 사용자 화면 / 중복 예약 방지
CREATE INDEX idx_appt_trade ON inspection_appointments (trade_id);

-- 운영 — 시간 구간 내 예약 전체 (일별 검수원 배치)
CREATE INDEX idx_appt_slot_status ON inspection_appointments (slot_start, status);

-- No-show batch — RESERVED 이면서 slot_end 지난 것
CREATE INDEX idx_appt_status_slot_end ON inspection_appointments (status, slot_end);
