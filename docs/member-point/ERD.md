
Claude configuration file at /Users/kimmo/.claude.json is corrupted: Unexpected end of JSON input

Claude configuration file at /Users/kimmo/.claude.json is corrupted
The corrupted file has been backed up to: /Users/kimmo/.claude.json.corrupted.1779973278578
A backup file exists at: /Users/kimmo/.claude.json.backup
You can manually restore it by running: cp "/Users/kimmo/.claude.json.backup" "/Users/kimmo/.claude.json"


Claude configuration file at /Users/kimmo/.claude.json is corrupted: Unterminated string in JSON at position 204298

Claude configuration file at /Users/kimmo/.claude.json is corrupted
The corrupted file has been backed up to: /Users/kimmo/.claude.json.corrupted.1779973278588
A backup file exists at: /Users/kimmo/.claude.json.backup
You can manually restore it by running: cp "/Users/kimmo/.claude.json.backup" "/Users/kimmo/.claude.json"

# Member-Point Service ERD

> 회원 관리, OAuth/JWT 인증, Point 지갑 및 히스토리를 담당하는 서비스의 데이터베이스 설계이다.

---

## 1. 테이블 목록

| 테이블 | 역할 |
|---|---|
| `member` | 회원 기본 정보, Point 잔액, 거주지역 |
| `point_history` | Point 적립/차감/정산 이력, 멱등성 보장 |

---

## 2. 테이블 DDL

### 2-1. member

```sql
CREATE TABLE member (
    id                      BIGINT          NOT NULL AUTO_INCREMENT,
    email                   VARCHAR(255)    NOT NULL UNIQUE,
    nickname                VARCHAR(50)     NOT NULL UNIQUE,
    point_balance           DECIMAL(10,2)   NOT NULL DEFAULT 0.00 CHECK (point_balance >= 0),
    role                    VARCHAR(20)     NOT NULL DEFAULT 'USER',  -- MemberRole
    residence_sido          VARCHAR(50),
    residence_sigu          VARCHAR(50),
    residence_changed_at    DATETIME,                               -- 거주지역 변경 쿨다운 30일 계산용
    oauth_provider          VARCHAR(20)     NOT NULL,               -- GOOGLE, KAKAO, APPLE
    oauth_id                VARCHAR(255)    NOT NULL,               -- OAuth 제공자의 사용자 ID
    deleted_at              DATETIME,
    created_at              DATETIME        NOT NULL,
    updated_at              DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_oauth (oauth_provider, oauth_id)                 -- OAuth 중복 가입 방지
);
```

### 2-2. point_history

```sql
CREATE TABLE point_history (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    member_id           BIGINT          NOT NULL,           -- member.id FK
    type                VARCHAR(50)     NOT NULL,           -- PointHistoryType
    amount              DECIMAL(10,2)   NOT NULL,           -- 양수: 적립, 음수: 차감
    balance_snapshot    DECIMAL(10,2)   NOT NULL,           -- 처리 후 잔액 스냅샷
    reason              VARCHAR(255),                       -- 사용자 노출용 설명
    reference_id        BIGINT,                             -- 연관 battle_id / market_id
    idempotency_key     VARCHAR(100)    UNIQUE,             -- 중복 처리 방지
    created_at          DATETIME        NOT NULL,
    updated_at          DATETIME        NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY (member_id) REFERENCES member(id)
);

-- 인덱스
CREATE INDEX idx_point_history_member_id ON point_history(member_id);
CREATE INDEX idx_point_history_created_at ON point_history(created_at);
CREATE INDEX idx_point_history_type ON point_history(type);
```

---

## 3. ERD 다이어그램

```mermaid
erDiagram
    member {
        BIGINT id PK
        VARCHAR(255) email UK
        VARCHAR(50) nickname UK
        DECIMAL(10,2) point_balance "CHECK >= 0"
        VARCHAR(20) role "DEFAULT USER"
        VARCHAR(50) residence_sido
        VARCHAR(50) residence_sigu
        DATETIME residence_changed_at "거주지 변경 쿨다운용"
        VARCHAR(20) oauth_provider
        VARCHAR(255) oauth_id
        DATETIME deleted_at
        DATETIME created_at
        DATETIME updated_at
    }

    point_history {
        BIGINT id PK
        BIGINT member_id FK
        VARCHAR(50) type "PointHistoryType"
        DECIMAL(10,2) amount "양수: 적립, 음수: 차감"
        DECIMAL(10,2) balance_snapshot "처리 후 잔액"
        VARCHAR(255) reason
        BIGINT reference_id "battle_id or market_id"
        VARCHAR(100) idempotency_key UK
        DATETIME created_at
        DATETIME updated_at
    }

    member ||--o{ point_history : "member_id"
```

---

## 4. Enum 정의

### 4-1. MemberRole

```java
public enum MemberRole {
    USER,       // 일반 사용자
    ADMIN       // 관리자 (Battle/Market 검수, Point 수동 지급)
}
```

### 4-2. PointHistoryType

```java
public enum PointHistoryType {
    // 적립 (EARN)
    EARN_SIGNUP,            // 신규 가입 보상
    EARN_VOTE,              // Battle 투표 참여 보상
    EARN_VOTE_WIN,          // Battle 승리 진영 추가 보상
    EARN_COMMENT,           // Battle 댓글 작성 보상
    EARN_BATTLE_APPROVED,   // Battle 주제 등록 승인 보상
    
    // 차감 (SPEND)
    SPEND_MARKET,           // Market 예측 참여
    SPEND_INSIGHT,          // Insight 열람 (교차분석, AI 리포트 등)
    SPEND_BATTLE_CREATE,    // Battle 주제 생성권
    SPEND_SLOT,             // 관심 지역 슬롯 확장
    
    // 정산 (SETTLE)
    SETTLE_MARKET,          // Market 정산 보상
    REFUND_MARKET,          // Market 무효 환불
    
    // 시스템 (SYSTEM)
    BURN                    // 시스템 소각 (정산 소수점 처리)
}
```

---

## 5. Point 획득 규칙

| 활동 | 타입 | 지급P | 제한 |
|---|---|---:|---|
| 신규 가입 | EARN_SIGNUP | 50P | 계정당 1회 |
| Battle 투표 | EARN_VOTE | 10P | 동일 Battle 1회 |
| Battle 승리 진영 | EARN_VOTE_WIN | +2P | 동일 Battle 1회 |
| Battle 댓글 | EARN_COMMENT | 2P | 동일 Battle 1회, 일일 최대 3건 |
| Battle 주제 승인 | EARN_BATTLE_APPROVED | 20P | 승인 시 1회 |

**일일 획득 제한**: Battle 기반 Point 일일 최대 100P (Market 정산 제외)

---

## 6. Point 소비 규칙

| 소비처 | 타입 | 소요P | 비고 |
|---|---|---:|---|
| Market 예측 | SPEND_MARKET | 10~500P | 정산 시 반환 가능 |
| 교차분석 열람 | SPEND_INSIGHT | 30P | Battle 건당 |
| 방문인증 필터 열람 | SPEND_INSIGHT | 30P | Battle 건당 |
| AI 리포트 | SPEND_INSIGHT | 80P | 건당 |
| Battle 주제 생성권 | SPEND_BATTLE_CREATE | 30P | 환불 없음 |
| 관심지역 슬롯 확장 | SPEND_SLOT | 50P/슬롯 | 최대 7개 |

---

## 7. 비즈니스 제약

### 7-1. Point 잔액 관리

- `point_balance`는 음수가 될 수 없다 (`CHECK` 제약)
- Point 차감 시 잔액 부족 검증을 반드시 수행한다
- 소수점 둘째 자리까지 저장, 셋째 자리 이하는 버림 처리한다

### 7-2. 멱등성 보장

- 모든 Point 처리는 `idempotency_key`를 필수로 사용한다
- 동일 키로 재요청 시 첫 번째 응답을 반환한다
- 서비스 간 Point 지급 API 호출 시 UUID 기반 멱등성 키를 사용한다

### 7-3. 거주지역 변경 제한

- 거주지역 변경은 30일마다 1회만 가능하다
- `residence_changed_at` 컬럼으로 마지막 변경일을 추적한다
- 최초 설정 시에는 쿨다운이 적용되지 않는다

### 7-4. Point 히스토리 관리

- `balance_snapshot`은 해당 트랜잭션 처리 후 잔액을 저장한다
- `reference_id`는 연관된 Battle ID 또는 Market ID를 저장한다
- 정산 소수점 버림으로 발생한 잔여 Point는 `BURN` 타입으로 기록한다

### 7-5. OAuth 계정 관리

- OAuth 제공자별 중복 가입을 방지한다
- `oauth_provider`와 `oauth_id` 조합으로 유일성을 보장한다
- 탈퇴 시 `deleted_at`만 설정하고 물리 삭제하지 않는다
