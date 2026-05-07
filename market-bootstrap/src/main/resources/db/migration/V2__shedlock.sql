-- ShedLock (여러 인스턴스 중 하나만 실행되도록 잠금을 잡는 라이브러리) 테이블 —
-- @Scheduled 메서드별 잠금 상태를 한 행씩 저장한다. lock_until 까지 다른 인스턴스는 점유 못함.
CREATE TABLE shedlock (
    name        VARCHAR(64)  NOT NULL,
    lock_until  TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_by   VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
