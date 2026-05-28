
Claude configuration file at /Users/kimmo/.claude.json is corrupted: Unterminated string in JSON at position 73455

Claude configuration file at /Users/kimmo/.claude.json is corrupted
The corrupted file has been backed up to: /Users/kimmo/.claude.json.corrupted.1779973278589
A backup file exists at: /Users/kimmo/.claude.json.backup
You can manually restore it by running: cp "/Users/kimmo/.claude.json.backup" "/Users/kimmo/.claude.json"

# docs/battle/ERD.md

> Battle 서비스의 상세 데이터베이스 설계 문서

---

## 1. 테이블 목록

| 테이블 | 역할 | 비고 |
|---|---|---|
| `battle` | Battle 주제, 상태 관리 | 투표 기간, 선택지 관리 |
| `battle_vote` | 개별 투표 기록 | 중복 투표 방지 |
| `comment` | Battle 댓글 | 댓글별 보상 지급 관리 |
| `point_reward_retry_queue` | Point 지급 재시도 큐 | 서비스 간 통신 장애 대응 |

---

## 2. DDL

### 2-1. battle

```sql
CREATE TABLE battle (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    title           VARCHAR(255)    NOT NULL,
    option_a        VARCHAR(100)    NOT NULL,
    option_b        VARCHAR(100)    NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',  -- BattleStatus
    created_by      BIGINT          NOT NULL,           -- member.id 참조 (REST)
    start_at        DATETIME        NOT NULL,
    end_at          DATETIME        NOT NULL,
    vote_count      INT             NOT NULL DEFAULT 0,
    deleted_at      DATETIME,
    created_at      DATETIME        NOT NULL,
    updated_at      DATETIME        NOT NULL,
    PRIMARY KEY (id)
);
```

### 2-2. battle_vote

```sql
CREATE TABLE battle_vote (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    battle_id       BIGINT          NOT NULL,
    member_id       BIGINT          NOT NULL,           -- member.id 참조 (REST)
    selected_option VARCHAR(10)     NOT NULL,           -- 'A' or 'B'
    created_at      DATETIME        NOT NULL,
    updated_at      DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_battle_vote (battle_id, member_id),   -- 중복 투표 방지
    CONSTRAINT ck_selected_option CHECK (selected_option IN ('A', 'B'))
);
```

### 2-3. comment

```sql
CREATE TABLE comment (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    battle_id       BIGINT          NOT NULL,
    member_id       BIGINT          NOT NULL,               -- member.id 참조 (REST)
    content         TEXT            NOT NULL,
    reward_given    BOOLEAN         NOT NULL DEFAULT FALSE, -- 동일 Battle 1회만 보상
    deleted_at      DATETIME,
    created_at      DATETIME        NOT NULL,
    updated_at      DATETIME        NOT NULL,
    PRIMARY KEY (id)
);
```

### 2-4. point_reward_retry_queue

```sql
CREATE TABLE point_reward_retry_queue (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    member_id       BIGINT          NOT NULL,
    reference_id    BIGINT          NOT NULL,           -- battle_id
    type            VARCHAR(50)     NOT NULL,           -- PointHistoryType
    amount          DECIMAL(10,2)   NOT NULL,
    idempotency_key VARCHAR(100)    NOT NULL UNIQUE,
    retry_count     INT             NOT NULL DEFAULT 0,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',  -- PENDING, SUCCESS, FAILED
    created_at      DATETIME        NOT NULL,
    updated_at      DATETIME        NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_retry_count CHECK (retry_count <= 3)  -- 최대 3회 재시도
);
```

---

## 3. Mermaid ERD

```mermaid
erDiagram
    battle {
        BIGINT id PK
        VARCHAR(255) title
        VARCHAR(100) option_a
        VARCHAR(100) option_b
        VARCHAR(20) status
        BIGINT created_by
        DATETIME start_at
        DATETIME end_at
        INT vote_count
        DATETIME deleted_at
        DATETIME created_at
        DATETIME updated_at
    }

    battle_vote {
        BIGINT id PK
        BIGINT battle_id FK
        BIGINT member_id
        VARCHAR(10) selected_option
        DATETIME created_at
        DATETIME updated_at
    }

    comment {
        BIGINT id PK
        BIGINT battle_id FK
        BIGINT member_id
        TEXT content
        BOOLEAN reward_given
        DATETIME deleted_at
        DATETIME created_at
        DATETIME updated_at
    }

    point_reward_retry_queue {
        BIGINT id PK
        BIGINT member_id
        BIGINT reference_id
        VARCHAR(50) type
        DECIMAL(10,2) amount
        VARCHAR(100) idempotency_key
        INT retry_count
        VARCHAR(20) status
        DATETIME created_at
        DATETIME updated_at
    }

    battle ||--o{ battle_vote : "1:N"
    battle ||--o{ comment : "1:N"
    battle ||--o{ point_reward_retry_queue : "1:N (reference_id)"
```

---

## 4. BattleStatus Enum

```java
public enum BattleStatus {
    PENDING,    // 검수 대기 - 사용자 등록 주제 관리자 승인 대기
    ACTIVE,     // 투표 진행 중 - 사용자 투표 참여 가능
    CLOSED,     // 투표 종료 - 결과 확정, 보상 지급 완료
    CANCELLED   // 강제 취소 - 부적절한 주제로 인한 관리자 취소
}
```

---

## 5. 비즈니스 제약사항

### 5-1. 투표 제약

- 한 사용자는 동일 Battle에 1회만 투표 가능 (`UNIQUE KEY uq_battle_vote`)
- `selected_option`은 'A' 또는 'B'만 허용
- Battle이 `ACTIVE` 상태일 때만 투표 가능
- 투표 기간(`start_at` ~ `end_at`) 내에만 투표 가능

### 5-2. 댓글 보상 제약

- 동일 Battle에 대한 댓글 보상은 사용자당 1회만 지급
- `reward_given` 플래그로 보상 지급 여부 관리
- 삭제된 댓글(`deleted_at IS NOT NULL`)에는 보상 지급 안 함

### 5-3. Point 보상 재시도 정책

- Member-Point Service 연계 실패 시 재시도 큐에 적재
- 최대 3회까지 재시도 (`retry_count <= 3`)
- 1분 간격으로 Scheduler가 재시도 처리
- 3회 실패 시 `status = 'FAILED'`로 처리하여 관리자 수동 확인

### 5-4. Battle 결과 공개 제약

- 투표 완료 전까지 결과 비공개 (블라인드 투표)
- 투표 참여자 10명 미만 시 통계적 신뢰성 부족으로 결과 비공개
- 종료 후 72시간 경과 시 미투표자에게도 기본 결과 공개

---

## 6. 인덱스 전략

```sql
-- 성능 최적화를 위한 인덱스
CREATE INDEX idx_battle_status_start_end ON battle (status, start_at, end_at);
CREATE INDEX idx_battle_vote_member ON battle_vote (member_id);
CREATE INDEX idx_comment_battle_member ON comment (battle_id, member_id);
CREATE INDEX idx_retry_queue_status ON point_reward_retry_queue (status, created_at);
```
