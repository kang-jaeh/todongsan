-- 서비스 간 정합성 대사 불일치 기록 테이블

USE memberpoint;

CREATE TABLE IF NOT EXISTS reconciliation_mismatch (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    check_type      VARCHAR(50)     NOT NULL,   -- POINT_TOTAL_BALANCE, MARKET_SPEND_MATCH
    market_id       BIGINT,
    expected_value  DECIMAL(18,2)   NOT NULL,
    actual_value    DECIMAL(18,2)   NOT NULL,
    diff_value      DECIMAL(18,2)   NOT NULL,
    detail          TEXT,
    resolved        BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_reconciliation_resolved (resolved, created_at)
);
