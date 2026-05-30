# Market Service ERD

> Market Service의 데이터베이스 설계 문서이다.  
> 본 도메인은 REST 기반 MSA 구조에서 독립 DB를 가지며, 포인트 예측 시장의 생성, 선택지 가격 관리, 예측 참여, 가격 변동 기록, 정산, 무효 환불을 담당한다.  
> 본 버전은 Member-Point 연동 정책인 `referenceType=MARKET_PREDICTION`, Prediction 단위 정산/환불 멱등성, item별 정산/환불 결과 처리를 반영한다.

---

# 1. 설계 방향

## 1-1. Market 도메인의 역할

Market Service는 사용자가 Point를 사용해 객관적으로 판정 가능한 지역 이벤트를 예측하는 도메인이다.

예시:

```text
다음 주 서울 아파트 매매가격지수는 상승할까?
이번 주 OO구 주택 가격 변동률은 얼마일까?
이번 달 서울 아파트 거래량은 지난달보다 증가할까?
2026년 6월 은마아파트 전용 84㎡ 최고 실거래가는 30억 이상일까?
```

Market은 Battle과 달리 사용자의 투표 결과로 승패가 결정되지 않는다.  
결과는 공공데이터, 외부 지표, 관리자 검수 기준에 의해 확정된다.

---

## 1-2. 지원하는 Market 선택지 유형

```text
YES_NO           : 상승 / 하락, YES / NO
MULTIPLE_CHOICE  : 여러 개의 일반 선택지
NUMERIC_RANGE    : 수치 구간 기반 선택지
```

---

## 1-3. Polymarket-lite 모델

본 프로젝트의 Market은 Polymarket의 핵심 아이디어인 “참여 시점에 따라 계약 가격이 달라지는 구조”를 단순화하여 적용한다.

MVP에서는 완전한 오더북, 매도, 지정가 주문, 유동성 공급자 구조는 구현하지 않는다.

MVP 구조:

```text
1. Market에는 여러 선택지가 존재한다.
2. 각 선택지는 현재 가격을 가진다.
3. 사용자가 특정 선택지에 Point를 사용하면, 참여 시점의 가격으로 계약 수량이 계산된다.
4. 다른 사용자의 참여가 누적되면 선택지별 가격이 변동된다.
5. 결과가 확정되면 승리 선택지의 계약 수량 비율에 따라 정산 풀을 분배한다.
```

정산 공식:

```text
개인 정산액 = 개인 보유 승리 계약 수량 / 승리 선택지 전체 계약 수량 × 정산 대상 풀
```

---

# 2. 서비스 간 참조 원칙

Market Service는 독립 DB를 가진다.

다른 서비스의 테이블을 직접 FK로 참조하지 않는다.  
다른 서비스 데이터가 필요한 경우 REST API를 통해 조회한다.

---

## 2-1. 외부 참조 ID

| 컬럼 | 의미 | 참조 방식 |
|---|---|---|
| `created_by` | Market 생성자 | Member-Point Service REST 조회 |
| `member_id` | 예측 참여자 | Member-Point Service REST 조회 |
| `settled_by` | 정산 관리자 | Member-Point Service REST 조회 |
| `voided_by` | 무효 처리 관리자 | Member-Point Service REST 조회 |

예를 들어 `market_prediction.member_id`는 Member-Point Service의 `member.id`를 의미하지만, Market DB에서 직접 FK를 걸지 않는다.

---

## 2-2. Member-Point point_history 연동 기준

Market이 Member-Point에 포인트 차감, 정산 지급, 환불 요청을 보낼 때는 다음 기준을 따른다.

```text
referenceType = MARKET_PREDICTION
referenceId = predictionId
```

| Market 상황 | Member-Point type | referenceType | referenceId |
|---|---|---|---:|
| 예측 참여 차감 | `SPEND_MARKET` | `MARKET_PREDICTION` | market_prediction.id |
| 정산 보상 지급 | `SETTLE_MARKET` | `MARKET_PREDICTION` | market_prediction.id |
| 무효 환불 | `REFUND_MARKET` | `MARKET_PREDICTION` | market_prediction.id |

정산/환불은 Market 단위가 아니라 Prediction 단위로 재시도된다.  
따라서 Member-Point의 `point_history.reference_id`에는 `marketId`가 아니라 `predictionId`가 들어가야 한다.

---

## 2-3. Market 내부 FK

Market Service 내부 테이블끼리는 FK를 사용한다.

```text
market.id
 ├── market_option.market_id
 ├── market_prediction.market_id
 ├── market_price_history.market_id
 ├── market_settlement.market_id
 └── market_void.market_id

market_option.id
 ├── market_prediction.option_id
 ├── market_price_history.option_id
 └── market_settlement.result_option_id

market_prediction.id
 ├── market_price_history.prediction_id
 ├── market_settlement_detail.prediction_id
 └── market_refund_detail.prediction_id
```

---

# 3. 테이블 목록

| 테이블 | 설명 |
|---|---|
| `market` | Market 주제, 판정 기준, 상태, 전체 풀 정보 |
| `market_option` | Market 선택지, 수치 구간, 현재 가격, 선택지별 풀 정보 |
| `market_prediction` | 사용자별 예측 참여 기록 |
| `market_price_history` | 선택지 가격 변동 이력 |
| `market_settlement` | Market 단위 정산 결과 |
| `market_settlement_detail` | 사용자별 정산 지급 상세 |
| `market_void` | Market 무효 처리 기록 |
| `market_refund_detail` | 무효 처리 시 사용자별 환불 상세 |

---

# 4. 테이블 상세 DDL

## 4-1. market

Market 주제, 판정 기준, 상태, 정산 정보를 저장한다.

```sql
CREATE TABLE market (
    id                          BIGINT          NOT NULL AUTO_INCREMENT,

    title                       VARCHAR(255)    NOT NULL,
    description                 TEXT,

    category                    VARCHAR(50)     NOT NULL,
    -- PRICE_INDEX, TRANSACTION_VOLUME, ACTUAL_PRICE, POLICY_EVENT

    answer_type                 VARCHAR(30)     NOT NULL,
    -- YES_NO, MULTIPLE_CHOICE, NUMERIC_RANGE

    metric_unit                 VARCHAR(30),
    -- PERCENT, COUNT, KRW, INDEX_POINT 등

    judge_data_source           VARCHAR(255)    NOT NULL,
    judge_criteria              TEXT            NOT NULL,
    judge_date                  DATE            NOT NULL,

    status                      VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    -- PENDING, ACTIVE, CLOSED, DATA_PENDING, SETTLEMENT_IN_PROGRESS, SETTLED, VOIDED

    close_at                    DATETIME        NOT NULL,
    settle_due_at               DATETIME,
    settled_at                  DATETIME,

    result_option_id            BIGINT,
    -- 확정된 승리 선택지. market_option.id 논리 참조
    -- 순환 FK 방지를 위해 DDL에서는 FK를 직접 걸지 않는다.

    result_value                DECIMAL(12,4),
    -- NUMERIC_RANGE 타입에서 실제 발표된 수치

    result_text                 VARCHAR(255),
    -- YES/NO 또는 일반 선택지 결과 보조 기록

    total_pool                  DECIMAL(10,2)   NOT NULL DEFAULT 0.00,
    -- 모든 CONFIRMED 예측 참여 Point 합계

    fee_rate                    DECIMAL(5,2)    NOT NULL DEFAULT 5.00,
    fee_amount                  DECIMAL(10,2)   NOT NULL DEFAULT 0.00,
    settlement_pool             DECIMAL(10,2)   NOT NULL DEFAULT 0.00,

    initial_virtual_liquidity   DECIMAL(10,2)   NOT NULL DEFAULT 100.00,
    -- 선택지별 초기 가상 유동성

    price_model                 VARCHAR(30)     NOT NULL DEFAULT 'POOL_SHARE',
    -- MVP에서는 POOL_SHARE 방식만 사용한다.

    created_by                  BIGINT          NOT NULL,
    -- Member-Point Service의 member.id 외부 참조

    deleted_at                  DATETIME,
    created_at                  DATETIME        NOT NULL,
    updated_at                  DATETIME        NOT NULL,

    PRIMARY KEY (id),

    INDEX idx_market_status (status),
    INDEX idx_market_close_at (close_at),
    INDEX idx_market_judge_date (judge_date),
    INDEX idx_market_status_close_at (status, close_at)
);
```

---

## 4-2. market_option

Market의 선택지와 선택지별 현재 가격 상태를 저장한다.

```sql
CREATE TABLE market_option (
    id                          BIGINT          NOT NULL AUTO_INCREMENT,

    market_id                   BIGINT          NOT NULL,

    option_code                 VARCHAR(20)     NOT NULL,
    -- YES, NO, A, B, C 등

    option_text                 VARCHAR(100)    NOT NULL,
    display_order               INT             NOT NULL DEFAULT 0,

    range_min                   DECIMAL(12,4),
    range_max                   DECIMAL(12,4),
    min_inclusive               BOOLEAN         NOT NULL DEFAULT TRUE,
    max_inclusive               BOOLEAN         NOT NULL DEFAULT FALSE,

    virtual_pool_amount         DECIMAL(10,2)   NOT NULL DEFAULT 100.00,
    real_pool_amount            DECIMAL(10,2)   NOT NULL DEFAULT 0.00,

    total_contract_quantity     DECIMAL(24,8)   NOT NULL DEFAULT 0.00000000,

    current_price               DECIMAL(18,8)   NOT NULL DEFAULT 0.00000000,
    -- 현재 1계약당 가격

    prediction_count            INT             NOT NULL DEFAULT 0,
    is_result                   BOOLEAN         NOT NULL DEFAULT FALSE,

    created_at                  DATETIME        NOT NULL,
    updated_at                  DATETIME        NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uq_market_option_code (market_id, option_code),
    INDEX idx_market_option_market_id (market_id),
    INDEX idx_market_option_market_order (market_id, display_order),

    CONSTRAINT fk_market_option_market
        FOREIGN KEY (market_id)
        REFERENCES market(id)
);
```

---

## 4-3. market_prediction

사용자의 예측 참여 기록을 저장한다.

핵심은 `point_amount`만 저장하는 것이 아니라 참여 시점의 가격과 계약 수량을 함께 저장하는 것이다.

```sql
CREATE TABLE market_prediction (
    id                                      BIGINT          NOT NULL AUTO_INCREMENT,

    market_id                               BIGINT          NOT NULL,
    option_id                               BIGINT          NOT NULL,

    member_id                               BIGINT          NOT NULL,
    -- Member-Point Service의 member.id 외부 참조

    point_amount                            DECIMAL(10,2)   NOT NULL,
    -- 사용자가 실제로 사용한 Point. 최소 10P, 최대 500P

    price_snapshot                          DECIMAL(18,8)   NOT NULL DEFAULT 0.00000000,
    -- 예측 참여 시점의 1계약당 가격

    contract_quantity                       DECIMAL(24,8)   NOT NULL DEFAULT 0.00000000,
    -- point_amount / price_snapshot

    expected_payout_per_contract_snapshot   DECIMAL(18,8),
    expected_multiplier_snapshot            DECIMAL(18,8),

    status                                  VARCHAR(30)     NOT NULL DEFAULT 'POINT_PENDING',
    -- POINT_PENDING, CONFIRMED, FAILED, POINT_UNKNOWN, SETTLED, REFUND_PENDING, REFUND_UNKNOWN, REFUNDED

    point_spend_idempotency_key             VARCHAR(150)    NOT NULL UNIQUE,
    -- Member-Point SPEND_MARKET 차감 요청 멱등성 키
    -- MARKET_PREDICTION_SPEND:market:{marketId}:member:{memberId}

    point_spend_reference_type              VARCHAR(50)     NOT NULL DEFAULT 'MARKET_PREDICTION',
    point_spend_reference_id                BIGINT          NOT NULL,
    -- Member-Point point_history.reference_id에 들어갈 값
    -- 원칙적으로 market_prediction.id와 동일

    settled_amount                          DECIMAL(10,2),
    refund_amount                           DECIMAL(10,2),

    fail_reason                             VARCHAR(255),

    created_at                              DATETIME        NOT NULL,
    updated_at                              DATETIME        NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uq_market_prediction_member (market_id, member_id),
    INDEX idx_market_prediction_market_status (market_id, status),
    INDEX idx_market_prediction_member_id (member_id),
    INDEX idx_market_prediction_option_status (option_id, status),
    INDEX idx_market_prediction_point_spend_key (point_spend_idempotency_key),

    CONSTRAINT fk_market_prediction_market
        FOREIGN KEY (market_id)
        REFERENCES market(id),

    CONSTRAINT fk_market_prediction_option
        FOREIGN KEY (option_id)
        REFERENCES market_option(id),

    CONSTRAINT chk_market_prediction_point_amount
        CHECK (point_amount >= 10 AND point_amount <= 500)
);
```

> 주의: `point_spend_reference_id`는 `market_prediction.id`와 동일하게 세팅한다.  
> MySQL에서 같은 row의 auto increment id를 insert 시점에 바로 기본값으로 넣기 어렵다면, Prediction을 `POINT_PENDING`으로 먼저 생성한 뒤 id를 확보하고 동일 트랜잭션 또는 후속 update로 `point_spend_reference_id`를 채운다.

---

## 4-4. market_price_history

사용자의 예측 참여로 선택지 가격이 변할 때마다 가격 이력을 저장한다.

```sql
CREATE TABLE market_price_history (
    id                              BIGINT          NOT NULL AUTO_INCREMENT,

    market_id                       BIGINT          NOT NULL,
    option_id                       BIGINT          NOT NULL,

    prediction_id                   BIGINT,
    -- 어떤 예측 참여로 인해 가격이 변경되었는지 추적

    price_before                    DECIMAL(18,8)   NOT NULL,
    price_after                     DECIMAL(18,8)   NOT NULL,

    real_pool_before                DECIMAL(10,2)   NOT NULL,
    real_pool_after                 DECIMAL(10,2)   NOT NULL,

    contract_quantity_before        DECIMAL(24,8)   NOT NULL,
    contract_quantity_after         DECIMAL(24,8)   NOT NULL,

    event_type                      VARCHAR(30)     NOT NULL DEFAULT 'PREDICTION_CONFIRMED',
    -- MARKET_OPENED, PREDICTION_CONFIRMED, MARKET_SETTLED, MARKET_VOIDED

    created_at                      DATETIME        NOT NULL,
    updated_at                      DATETIME        NOT NULL,

    PRIMARY KEY (id),

    INDEX idx_price_history_market_option (market_id, option_id),
    INDEX idx_price_history_prediction (prediction_id),
    INDEX idx_price_history_market_created (market_id, created_at),

    CONSTRAINT fk_price_history_market
        FOREIGN KEY (market_id)
        REFERENCES market(id),

    CONSTRAINT fk_price_history_option
        FOREIGN KEY (option_id)
        REFERENCES market_option(id),

    CONSTRAINT fk_price_history_prediction
        FOREIGN KEY (prediction_id)
        REFERENCES market_prediction(id)
);
```

---

## 4-5. market_settlement

Market 단위의 정산 결과를 저장한다.

Market 하나당 정산은 원칙적으로 한 번만 수행된다.  
다만 실제 Member-Point 지급 멱등성은 이 테이블이 아니라 `market_settlement_detail.idempotency_key`가 담당한다.

```sql
CREATE TABLE market_settlement (
    id                              BIGINT          NOT NULL AUTO_INCREMENT,

    market_id                       BIGINT          NOT NULL,
    result_option_id                BIGINT          NOT NULL,

    total_pool                      DECIMAL(10,2)   NOT NULL,
    fee_rate                        DECIMAL(5,2)    NOT NULL,
    fee_amount                      DECIMAL(10,2)   NOT NULL,
    settlement_pool                 DECIMAL(10,2)   NOT NULL,

    winning_contract_quantity       DECIMAL(24,8)   NOT NULL,
    payout_per_contract             DECIMAL(18,8)   NOT NULL,

    burned_point_amount             DECIMAL(10,2)   NOT NULL DEFAULT 0.00,

    status                          VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    -- PENDING, IN_PROGRESS, COMPLETED, PARTIAL_FAILED, FAILED

    settled_by                      BIGINT,
    -- 관리자 member.id 외부 참조

    settled_at                      DATETIME,

    created_at                      DATETIME        NOT NULL,
    updated_at                      DATETIME        NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uq_market_settlement_market (market_id),

    CONSTRAINT fk_market_settlement_market
        FOREIGN KEY (market_id)
        REFERENCES market(id),

    CONSTRAINT fk_market_settlement_result_option
        FOREIGN KEY (result_option_id)
        REFERENCES market_option(id)
);
```

> 기존 batch 단위 `settlementId` 또는 `market_settlement.idempotency_key`는 사용하지 않는다.  
> 정산 지급은 사용자별 detail item 단위로 멱등성을 보장한다.

---

## 4-6. market_settlement_detail

사용자별 정산 지급 상세를 저장한다.

승리 선택지에 참여한 사용자별로 하나씩 생성된다.

```sql
CREATE TABLE market_settlement_detail (
    id                              BIGINT          NOT NULL AUTO_INCREMENT,

    settlement_id                   BIGINT          NOT NULL,
    prediction_id                   BIGINT          NOT NULL,

    member_id                       BIGINT          NOT NULL,
    -- Member-Point Service의 member.id 외부 참조

    original_point_amount           DECIMAL(10,2)   NOT NULL,
    contract_quantity               DECIMAL(24,8)   NOT NULL,

    payout_per_contract             DECIMAL(18,8)   NOT NULL,
    settled_amount                  DECIMAL(10,2)   NOT NULL,
    profit_amount                   DECIMAL(10,2)   NOT NULL,

    status                          VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    -- PENDING, SUCCESS, FAILED, UNKNOWN

    idempotency_key                 VARCHAR(150)    NOT NULL UNIQUE,
    -- 사용자별 정산 지급 멱등성 키
    -- MARKET_SETTLEMENT_REWARD:market:{marketId}:prediction:{predictionId}:member:{memberId}

    point_reference_type            VARCHAR(50)     NOT NULL DEFAULT 'MARKET_PREDICTION',
    point_reference_id              BIGINT          NOT NULL,
    -- Member-Point point_history.reference_id에 들어갈 값
    -- prediction_id와 동일

    fail_reason                     VARCHAR(255),

    created_at                      DATETIME        NOT NULL,
    updated_at                      DATETIME        NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uq_settlement_detail_prediction (prediction_id),
    INDEX idx_settlement_detail_member_id (member_id),
    INDEX idx_settlement_detail_settlement_id (settlement_id),
    INDEX idx_settlement_detail_status (status),
    INDEX idx_settlement_detail_idempotency_key (idempotency_key),

    CONSTRAINT fk_settlement_detail_settlement
        FOREIGN KEY (settlement_id)
        REFERENCES market_settlement(id),

    CONSTRAINT fk_settlement_detail_prediction
        FOREIGN KEY (prediction_id)
        REFERENCES market_prediction(id)
);
```

---

## 4-7. market_void

Market 무효 처리 기록을 저장한다.

무효 처리 시 모든 CONFIRMED 예측 참여는 전액 환불 대상이 된다.  
다만 실제 Member-Point 환불 멱등성은 이 테이블이 아니라 `market_refund_detail.idempotency_key`가 담당한다.

```sql
CREATE TABLE market_void (
    id                              BIGINT          NOT NULL AUTO_INCREMENT,

    market_id                       BIGINT          NOT NULL,

    reason_type                     VARCHAR(50)     NOT NULL,
    -- DATA_UNAVAILABLE, ADMIN_ERROR, MARKET_CANCELLED, NO_TRANSACTION, ETC

    reason_detail                   TEXT,

    refund_status                   VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    -- PENDING, IN_PROGRESS, COMPLETED, PARTIAL_FAILED, FAILED

    voided_by                       BIGINT,
    -- 관리자 member.id 외부 참조

    voided_at                       DATETIME        NOT NULL,

    created_at                      DATETIME        NOT NULL,
    updated_at                      DATETIME        NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uq_market_void_market (market_id),

    CONSTRAINT fk_market_void_market
        FOREIGN KEY (market_id)
        REFERENCES market(id)
);
```

> 기존 batch 단위 `refundId` 또는 `market_void.idempotency_key`는 사용하지 않는다.  
> 환불은 사용자별 detail item 단위로 멱등성을 보장한다.

---

## 4-8. market_refund_detail

Market 무효 처리 시 사용자별 환불 상세를 저장한다.

환불 API 호출의 멱등성과 실패 재시도 추적을 위해 별도 테이블로 분리한다.

```sql
CREATE TABLE market_refund_detail (
    id                              BIGINT          NOT NULL AUTO_INCREMENT,

    market_void_id                  BIGINT          NOT NULL,
    prediction_id                   BIGINT          NOT NULL,

    member_id                       BIGINT          NOT NULL,
    -- Member-Point Service의 member.id 외부 참조

    refund_amount                   DECIMAL(10,2)   NOT NULL,
    -- 원칙적으로 prediction.point_amount 전액 환불

    status                          VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    -- PENDING, SUCCESS, FAILED, UNKNOWN

    idempotency_key                 VARCHAR(150)    NOT NULL UNIQUE,
    -- 사용자별 환불 지급 멱등성 키
    -- MARKET_REFUND:market:{marketId}:prediction:{predictionId}:member:{memberId}

    point_reference_type            VARCHAR(50)     NOT NULL DEFAULT 'MARKET_PREDICTION',
    point_reference_id              BIGINT          NOT NULL,
    -- Member-Point point_history.reference_id에 들어갈 값
    -- prediction_id와 동일

    fail_reason                     VARCHAR(255),

    created_at                      DATETIME        NOT NULL,
    updated_at                      DATETIME        NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uq_refund_detail_prediction (prediction_id),
    INDEX idx_refund_detail_member_id (member_id),
    INDEX idx_refund_detail_void_id (market_void_id),
    INDEX idx_refund_detail_status (status),
    INDEX idx_refund_detail_idempotency_key (idempotency_key),

    CONSTRAINT fk_refund_detail_void
        FOREIGN KEY (market_void_id)
        REFERENCES market_void(id),

    CONSTRAINT fk_refund_detail_prediction
        FOREIGN KEY (prediction_id)
        REFERENCES market_prediction(id)
);
```

---

# 5. Enum 정의

## 5-1. MarketAnswerType

```java
public enum MarketAnswerType {
    YES_NO,
    MULTIPLE_CHOICE,
    NUMERIC_RANGE
}
```

## 5-2. MarketStatus

```java
public enum MarketStatus {
    PENDING,
    ACTIVE,
    CLOSED,
    DATA_PENDING,
    SETTLEMENT_IN_PROGRESS,
    SETTLED,
    VOIDED
}
```

## 5-3. PredictionStatus

```java
public enum PredictionStatus {
    POINT_PENDING,
    CONFIRMED,
    FAILED,
    POINT_UNKNOWN,
    SETTLED,
    REFUND_PENDING,
    REFUND_UNKNOWN,
    REFUNDED
}
```

## 5-4. SettlementStatus

```java
public enum SettlementStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    PARTIAL_FAILED,
    FAILED
}
```

## 5-5. DetailProcessStatus

```java
public enum DetailProcessStatus {
    PENDING,
    SUCCESS,
    FAILED,
    UNKNOWN
}
```

## 5-6. VoidReasonType

```java
public enum VoidReasonType {
    DATA_UNAVAILABLE,
    ADMIN_ERROR,
    MARKET_CANCELLED,
    NO_TRANSACTION,
    ETC
}
```

## 5-7. PointReferenceType

```java
public enum PointReferenceType {
    MARKET_PREDICTION
}
```

> 실제 enum은 Member-Point 공통 정책에서 `MARKET_PREDICTION`, `BATTLE`, `INSIGHT_REPORT`를 함께 정의한다.  
> Market Service에서는 `MARKET_PREDICTION`만 사용한다.

---

# 6. 선택지 설계 규칙

## 6-1. YES_NO Market

```text
YES
NO
```

## 6-2. MULTIPLE_CHOICE Market

일반 다중 선택지는 `option_code`, `option_text`, `display_order`로 표현한다.

## 6-3. NUMERIC_RANGE Market

수치 구간형 선택지는 `range_min`, `range_max`, `min_inclusive`, `max_inclusive`를 사용한다.

예시:

```text
0.30% 이상 ~ 0.40% 미만

range_min = 0.3000
range_max = 0.4000
min_inclusive = TRUE
max_inclusive = FALSE
```

## 6-4. NUMERIC_RANGE 검증 규칙

```text
1. 선택지 범위가 서로 겹치면 안 된다.
2. 선택지 범위 사이에 빈 구간이 있으면 안 된다.
3. 경계값 포함 여부가 명확해야 한다.
4. 실제 결과값은 정확히 하나의 선택지에만 매칭되어야 한다.
```

---

# 7. 가격 계산 규칙

## 7-1. 현재 가격 계산

MVP에서는 `POOL_SHARE` 방식을 사용한다.

```text
option_effective_pool = option.real_pool_amount + option.virtual_pool_amount

current_price = option_effective_pool / sum(all option effective_pool)
```

## 7-2. 계약 수량 계산

```text
contract_quantity = point_amount / price_snapshot
```

## 7-3. 가격 갱신 대상

예측 참여 후 모든 선택지의 `current_price`를 재계산한다.

```text
1. 선택한 option.real_pool_amount 증가
2. 선택한 option.total_contract_quantity 증가
3. 모든 option의 current_price 재계산
4. market_price_history 저장
```

---

# 8. 정산 공식

## 8-1. 핵심 공식

```text
settlement_pool = total_pool - fee_amount

payout_per_contract = settlement_pool / winning_contract_quantity

user_settled_amount = user_contract_quantity × payout_per_contract
```

## 8-2. 소수점 처리

```text
정산 금액은 DECIMAL(10,2) 기준으로 최종 저장한다.
소수점 처리 후 남는 잔여 금액은 burned_point_amount로 기록한다.
```

## 8-3. 수수료 소각

```text
fee_amount = total_pool × fee_rate
burned_point_amount = fee_amount + 소수점 처리 잔여 금액
```

---

# 9. 주요 비즈니스 제약

## 9-1. 참여 제약

```text
1. MarketStatus = ACTIVE인 경우에만 참여 가능
2. 한 사용자는 하나의 Market에 하나의 Prediction만 가능
3. point_amount는 10 이상 500 이하
4. 동일 market_id, member_id는 UNIQUE 제약으로 차단
```

## 9-2. 포인트 차감 기록 우선 원칙

예측 참여 시 Prediction을 먼저 `POINT_PENDING`으로 저장하고 Member-Point 차감을 요청한다.

```text
Prediction POINT_PENDING 저장
→ Member-Point SPEND_MARKET 요청
→ 성공 시 CONFIRMED
→ 타임아웃/5xx 시 POINT_UNKNOWN
```

## 9-3. 정산/환불 item별 멱등성

정산/환불은 batch API를 사용해도 item별 idempotencyKey를 사용한다.

```text
정산:
MARKET_SETTLEMENT_REWARD:market:{marketId}:prediction:{predictionId}:member:{memberId}

환불:
MARKET_REFUND:market:{marketId}:prediction:{predictionId}:member:{memberId}
```

## 9-4. VOIDED 처리 제약

```text
VOIDED 가능:
PENDING, ACTIVE, CLOSED, DATA_PENDING

VOIDED 불가능:
SETTLEMENT_IN_PROGRESS, SETTLED
```

정산이 시작된 Market은 관리자도 VOIDED 처리할 수 없다.

---

# 10. 주요 흐름

## 10-1. 예측 참여 흐름

```text
1. Market 조회
2. ACTIVE 상태 검증
3. 선택지 검증
4. 중복 참여 검증
5. market_prediction POINT_PENDING 저장
6. Member-Point 포인트 차감 요청
   - referenceType = MARKET_PREDICTION
   - referenceId = predictionId
7. MarketOption 가격 갱신
8. PriceHistory 저장
9. Prediction CONFIRMED
```

## 10-2. 정산 흐름

```text
1. CLOSED 또는 DATA_PENDING Market 조회
2. Atomic Update로 SETTLEMENT_IN_PROGRESS 획득
3. market_settlement 생성
4. 승리 Prediction 기준 market_settlement_detail 생성
5. Member-Point 정산 batch 호출
   - item별 idempotencyKey
   - referenceType = MARKET_PREDICTION
   - referenceId = predictionId
6. item별 results 처리
7. 모든 detail SUCCESS이면 Market SETTLED
8. 일부 실패면 SETTLEMENT_IN_PROGRESS 유지 후 retry
```

## 10-3. 무효 환불 흐름

```text
1. VOIDED 가능 상태 검증
2. Market VOIDED
3. market_void 생성
4. 환불 대상 Prediction 기준 market_refund_detail 생성
5. Member-Point 환불 batch 호출
   - item별 idempotencyKey
   - referenceType = MARKET_PREDICTION
   - referenceId = predictionId
6. item별 results 처리
7. 실패 건만 retry
```

---

# 11. 서비스 간 REST 연계

## 11-1. 예측 참여 시 Point 차감

```http
POST /api/v1/points/spend
Idempotency-Key: MARKET_PREDICTION_SPEND:market:{marketId}:member:{memberId}
```

```json
{
  "memberId": 10,
  "type": "SPEND_MARKET",
  "amount": "100.00",
  "referenceType": "MARKET_PREDICTION",
  "referenceId": 1001,
  "reason": "Market 예측 참여"
}
```

## 11-2. 정산 시 Point 지급

```http
POST /api/v1/points/settlements
```

```json
{
  "marketId": 7,
  "items": [
    {
      "predictionId": 1001,
      "memberId": 10,
      "amount": "185.30",
      "referenceType": "MARKET_PREDICTION",
      "referenceId": 1001,
      "reason": "Market 정산 보상",
      "idempotencyKey": "MARKET_SETTLEMENT_REWARD:market:7:prediction:1001:member:10"
    }
  ]
}
```

## 11-3. 무효 처리 시 Point 환불

```http
POST /api/v1/points/refunds
```

```json
{
  "marketId": 7,
  "items": [
    {
      "predictionId": 1001,
      "memberId": 10,
      "amount": "100.00",
      "referenceType": "MARKET_PREDICTION",
      "referenceId": 1001,
      "reason": "Market 무효 환불",
      "idempotencyKey": "MARKET_REFUND:market:7:prediction:1001:member:10"
    }
  ]
}
```

---

# 12. 인덱스 전략

## 12-1. Market 조회

```sql
CREATE INDEX idx_market_status ON market(status);
CREATE INDEX idx_market_close_at ON market(close_at);
CREATE INDEX idx_market_status_close_at ON market(status, close_at);
```

## 12-2. Prediction 조회

```sql
CREATE UNIQUE INDEX uq_market_prediction_member
ON market_prediction(market_id, member_id);

CREATE INDEX idx_market_prediction_market_status
ON market_prediction(market_id, status);

CREATE INDEX idx_market_prediction_member_id
ON market_prediction(member_id);
```

## 12-3. 정산 재시도 조회

```sql
CREATE INDEX idx_settlement_detail_status
ON market_settlement_detail(status);

CREATE INDEX idx_settlement_detail_idempotency_key
ON market_settlement_detail(idempotency_key);
```

## 12-4. 환불 재시도 조회

```sql
CREATE INDEX idx_refund_detail_status
ON market_refund_detail(status);

CREATE INDEX idx_refund_detail_idempotency_key
ON market_refund_detail(idempotency_key);
```

## 12-5. Price History 조회

```sql
CREATE INDEX idx_price_history_market_created
ON market_price_history(market_id, created_at);
```

---

# 13. MVP 구현 범위

## 13-1. MVP 필수 테이블

```text
market
market_option
market_prediction
market_price_history
market_settlement
market_settlement_detail
market_void
market_refund_detail
```

## 13-2. MVP 제외 기능

```text
오더북
지정가 주문
매도
유동성 공급자
포지션 합산 테이블
2차 거래
```

---

# 14. 최종 변경 요약

이번 버전의 핵심 변경 사항은 다음과 같다.

```text
1. MarketStatus에 SETTLEMENT_IN_PROGRESS 반영
2. PredictionStatus에 POINT_PENDING, POINT_UNKNOWN, REFUND_PENDING, REFUND_UNKNOWN 반영
3. Member-Point 연동 기준 referenceType=MARKET_PREDICTION, referenceId=predictionId 반영
4. 정산/환불은 batch 단위가 아니라 item 단위 idempotencyKey로 보장
5. market_settlement.idempotency_key, market_void.idempotency_key 제거
6. market_settlement_detail, market_refund_detail의 idempotency_key를 정산/환불의 핵심 멱등성 키로 사용
7. 정산/환불 실패 재시도를 위해 detail status에 UNKNOWN 포함
8. 가격 이력 페이징을 위한 market_id, created_at 인덱스 추가
```
