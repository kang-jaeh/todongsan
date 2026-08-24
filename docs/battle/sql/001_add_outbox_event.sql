-- Battle 서비스 Transactional Outbox 테이블
-- 비즈니스 변경과 같은 트랜잭션에서 이벤트를 기록하고,
-- 폴링 퍼블리셔가 Kafka로 발행한 뒤 PUBLISHED로 전이한다.

USE battle;

CREATE TABLE IF NOT EXISTS outbox_event (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    aggregate_type  VARCHAR(50)     NOT NULL,          -- 예: BATTLE
    aggregate_id    BIGINT          NOT NULL,           -- 예: battle.id
    event_type      VARCHAR(100)    NOT NULL,           -- 예: BATTLE_REWARD_REQUESTED
    event_id        VARCHAR(36)     NOT NULL,           -- UUID, 컨슈머 멱등성 판정 기준
    payload         JSON            NOT NULL,           -- 봉투(envelope) 전체 JSON
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',  -- PENDING → PUBLISHED
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uq_outbox_event_id (event_id),
    INDEX idx_outbox_status_created (status, created_at)
);
