-- Market 서비스 Transactional Outbox + Inbox 테이블.
-- Outbox: prediction.created 이벤트 발행용.
-- Inbox: point.deducted / point.deduction.failed 중복 소비 차단용.

USE market;

CREATE TABLE IF NOT EXISTS outbox_event (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    aggregate_type  VARCHAR(50)     NOT NULL,
    aggregate_id    BIGINT          NOT NULL,
    event_type      VARCHAR(100)    NOT NULL,
    event_id        VARCHAR(36)     NOT NULL,
    payload         JSON            NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uq_outbox_event_id (event_id),
    INDEX idx_outbox_status_created (status, created_at)
);

CREATE TABLE IF NOT EXISTS processed_event (
    event_id        VARCHAR(36)     NOT NULL,
    processed_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id)
);
