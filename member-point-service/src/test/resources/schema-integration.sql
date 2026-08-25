-- Testcontainers 통합 테스트용 스키마 (memberpoint DB)
-- infra/mysql/init/02-memberpoint-schema.sql과 동일하되 USE 문 제거

CREATE TABLE IF NOT EXISTS member (
    id                   BIGINT          NOT NULL AUTO_INCREMENT,
    email                VARCHAR(255)    NULL,
    nickname             VARCHAR(50)     NOT NULL,
    point_balance        DECIMAL(10,2)   NOT NULL DEFAULT 0.00,
    role                 VARCHAR(20)     NOT NULL DEFAULT 'USER',
    residence_sido       VARCHAR(50),
    residence_sigu       VARCHAR(50),
    residence_changed_at DATETIME,
    age_group            VARCHAR(20),
    gender               VARCHAR(10),
    oauth_provider       VARCHAR(20)     NOT NULL,
    oauth_id             VARCHAR(255)    NOT NULL,
    deleted_at           DATETIME,
    created_at           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_member_email (email),
    UNIQUE KEY uq_member_nickname (nickname),
    UNIQUE KEY uq_oauth (oauth_provider, oauth_id),
    CONSTRAINT chk_member_point_balance CHECK (point_balance >= 0)
);

CREATE TABLE IF NOT EXISTS oauth_token (
    id                       BIGINT      NOT NULL AUTO_INCREMENT,
    member_id                BIGINT      NOT NULL,
    provider                 VARCHAR(20) NOT NULL,
    access_token             TEXT        NOT NULL,
    refresh_token            TEXT        NOT NULL,
    access_token_expires_at  DATETIME    NOT NULL,
    refresh_token_expires_at DATETIME    NOT NULL,
    created_at               DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_oauth_token_member_id (member_id),
    CONSTRAINT fk_oauth_token_member
        FOREIGN KEY (member_id) REFERENCES member(id)
);

CREATE TABLE IF NOT EXISTS point_history (
    id               BIGINT          NOT NULL AUTO_INCREMENT,
    member_id        BIGINT          NOT NULL,
    type             VARCHAR(50)     NOT NULL,
    amount           DECIMAL(10,2)   NOT NULL,
    balance_snapshot DECIMAL(10,2)   NOT NULL,
    reason           VARCHAR(255),
    reference_type   VARCHAR(50)     NULL,
    reference_id     BIGINT          NULL,
    idempotency_key  VARCHAR(150)    NOT NULL,
    request_hash     VARCHAR(64)     NULL,
    status           VARCHAR(20)     NOT NULL DEFAULT 'SUCCEEDED',
    fail_reason      VARCHAR(50)     NULL,
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_point_history_idempotency_key (idempotency_key),
    INDEX idx_point_history_member_id (member_id),
    INDEX idx_point_history_created_at (created_at),
    INDEX idx_point_history_type (type),
    INDEX idx_point_history_reference (reference_type, reference_id),
    CONSTRAINT chk_point_history_amount CHECK (amount > 0),
    CONSTRAINT fk_point_history_member
        FOREIGN KEY (member_id) REFERENCES member(id)
);

CREATE TABLE IF NOT EXISTS reconciliation_mismatch (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    check_type      VARCHAR(50)     NOT NULL,
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
