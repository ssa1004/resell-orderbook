-- SAGA 보상 트랜잭션의 명시 멱등성 로그 (ADR-0023).
--
-- 배경:
--   InspectionFailed → RefundBuyerService → PG.refund 같은 보상 트랜잭션은 메시지 컨슈머가
--   at-least-once (적어도 한 번 — 중복 가능) 라 같은 입력이 두 번 들어올 수 있다. DB UNIQUE
--   (uk_payout_trade, uk_refund_trade 등) 가 1차 방어이지만, 외부 호출 (PG / 은행) 이 *유실 또는
--   중복* 되는 시나리오에서는 부족하다 — 첫 시도의 PG 호출이 성공했으나 응답이 유실되어
--   서비스가 재시도하면 PG 가 두 번 호출될 수 있다.
--
-- 결정:
--   compensation_log 에 (operation, business_key) UNIQUE — 같은 보상 1건당 row 1개. 외부 호출
--   직전에 INSERT (= 자리 점유), 결과를 row 에 박아두고, 재시도 시 row 가 있으면 외부 호출 없이
--   캐시된 결과를 그대로 반환. 토스/카카오페이의 PG 멱등성 키 패턴.

CREATE TABLE compensation_log (
    operation       VARCHAR(40)  NOT NULL,    -- "REFUND", "SETTLE_PAYOUT", ...
    business_key    VARCHAR(100) NOT NULL,    -- 일반적으로 tradeId
    status          VARCHAR(20)  NOT NULL,    -- "IN_PROGRESS", "COMPLETED", "FAILED"
    response_code   VARCHAR(40),              -- 외부 호출의 결과 코드 (null = 미완)
    response_message VARCHAR(500),
    external_id     VARCHAR(200),             -- pgRefundId / bankTransferId 등 외부 시스템 식별자
    started_at      TIMESTAMP    NOT NULL,
    completed_at    TIMESTAMP,
    -- 같은 (operation, business_key) 는 1번만 — 두 번째 INSERT 는 DB 가 거절.
    CONSTRAINT pk_compensation_log PRIMARY KEY (operation, business_key)
);

-- 운영 모니터링 — IN_PROGRESS 가 오래 머물면 외부 호출이 stuck. status + started_at 정렬.
CREATE INDEX ix_compensation_log_status ON compensation_log (status, started_at);
