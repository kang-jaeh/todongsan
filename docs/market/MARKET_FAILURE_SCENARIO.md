# MARKET_FAILURE_SCENARIO_v2.md

> Market 서비스에서 발생할 수 있는 실패 시나리오와 상태 전이, 재시도 정책, 복구 방식을 정의한다.  
> 이 문서는 `MARKET_ERROR_CODE.md`와 `MARKET_API_SPEC.md` 작성 전 기준 문서로 사용한다.  
> Market 서비스는 REST API 기반 MSA 환경에서 Member-Point 서비스와 직접 통신하므로, 포인트 차감/정산/환불 과정에서 발생하는 타임아웃, 서버 장애, 중복 요청, 동시성 이슈를 반드시 고려한다.

---

## 1. 문서 목적

Market 서비스의 실패 처리는 단순히 ErrorCode를 반환하는 것에서 끝나지 않는다.

Market 서비스는 다음 흐름을 가진다.

```text
Market 생성
→ 예측 참여
→ 포인트 차감
→ Market 마감
→ 공공 데이터 수집
→ 정답 선택지 계산
→ 정산
→ 포인트 지급 또는 환불
```

따라서 각 실패 상황마다 다음 내용을 명확히 정의해야 한다.

| 항목 | 설명 |
|---|---|
| 발생 시점 | 어떤 기능 수행 중 실패가 발생했는지 |
| 실패 원인 | 어떤 이유로 실패했는지 |
| 상태 변화 | Market 또는 Prediction 상태가 어떻게 변하는지 |
| 재시도 여부 | Scheduler 또는 수동 재시도가 필요한지 |
| 복구 방식 | 자동 복구, 관리자 확인, 보상 트랜잭션 여부 |
| 관련 ErrorCode | API 응답 또는 내부 처리에 사용할 ErrorCode |

---

## 2. 상태 정의

### 2-1. MarketStatus

```java
public enum MarketStatus {
    PENDING,                  // 검수 대기
    ACTIVE,                   // 예측 참여 가능
    CLOSED,                   // 예측 마감
    DATA_PENDING,             // 정산 데이터 수집 대기
    SETTLEMENT_IN_PROGRESS,   // 정산 진행 중
    SETTLED,                  // 정산 완료
    VOIDED                    // 무효 처리
}
```

### 2-2. PredictionStatus

```java
public enum PredictionStatus {
    POINT_PENDING,    // Prediction 선저장 완료, 포인트 차감 요청 전/요청 중
    CONFIRMED,        // 포인트 차감 완료, 가격 확정 완료, 예측 참여 확정
    FAILED,           // 예측 참여 실패
    POINT_UNKNOWN,    // 포인트 차감 여부 불명확
    SETTLED,          // 정산 완료
    REFUND_PENDING,   // 환불 요청 중
    REFUND_UNKNOWN,   // 환불 여부 불명확
    REFUNDED          // 환불 완료
}
```

---

## 3. 핵심 처리 원칙

### 3-1. Prediction은 포인트 차감 전에 먼저 저장한다

예측 참여는 포인트 차감 전에 `market_prediction`을 먼저 `POINT_PENDING` 상태로 저장한다.

```text
[트랜잭션 A]
Prediction POINT_PENDING 저장
→ 커밋

[트랜잭션 밖]
Member-Point 포인트 차감 요청

[트랜잭션 B]
포인트 차감 성공 확인
→ Market row 비관적 락 획득
→ 해당 Market의 모든 MarketOption row 비관적 락 획득
→ priceSnapshot, contractQuantity 확정
→ pool 갱신
→ 전체 선택지 가격 재계산
→ PriceHistory 저장
→ Prediction CONFIRMED 변경
→ 커밋
```

포인트 차감 후 Prediction을 저장하는 방식은 사용하지 않는다.

이유:

```text
포인트 차감 성공
→ Prediction 저장 실패
→ 유저 포인트는 차감되었지만 예측 기록이 남지 않음
```

또한 DB 락을 잡은 트랜잭션 안에서 Member-Point HTTP API를 호출하지 않는다.

이유:

```text
외부 HTTP 응답 지연
→ DB 커넥션과 락 장시간 점유
→ lock wait 증가
→ 커넥션 풀 고갈
→ 장애 전파
```

---

### 3-2. 타임아웃은 실패가 아니라 처리 여부 불명확 상태로 본다

```text
포인트 차감 타임아웃
→ PredictionStatus = POINT_UNKNOWN

환불 타임아웃
→ PredictionStatus = REFUND_UNKNOWN
```

이후 Scheduler가 Idempotency-Key를 이용해 Member-Point 서비스의 처리 이력을 조회한다.

---

### 3-3. POINT_PENDING 고착 상태도 대사 대상에 포함한다

다음 상황에서는 `POINT_PENDING`에 고착될 수 있다.

```text
Prediction POINT_PENDING 저장
→ Member-Point 포인트 차감 요청
→ Member-Point 포인트 차감 성공
→ Market이 200 OK 응답 수신
→ Prediction CONFIRMED 업데이트 직전 Market 서버 장애 발생
→ PredictionStatus = POINT_PENDING 상태로 고착
```

Scheduler 대사 대상:

```text
1. PredictionStatus = POINT_UNKNOWN
2. updatedAt 기준 3분 이상 지난 POINT_PENDING
```

대사 결과:

```text
차감 성공 확인 → CONFIRMED
차감 실패 확인 → FAILED
처리 이력 없음 → 포인트 차감 재시도 또는 FAILED 처리
```

---

### 3-4. 한 사용자는 하나의 Market에 하나의 Prediction만 가질 수 있다

Market은 2지선다 형태일 수도 있고, 여러 선택지 중 1개를 고르는 형태일 수도 있다.

공통 규칙:

```text
하나의 Market에 대해 한 사용자는 정확히 하나의 선택지만 고를 수 있다.
```

DB 제약 조건:

```sql
UNIQUE (market_id, member_id)
```

예측 참여 포인트 차감 Idempotency-Key:

```text
MARKET_PREDICTION_SPEND:market:{marketId}:member:{memberId}
```

`optionId`는 예측 참여 포인트 차감 멱등성 키에 포함하지 않는다.

이유:

```text
같은 사용자가 같은 Market에서 서로 다른 선택지를 동시에 요청하더라도,
포인트 차감은 Market 단위로 1회만 발생해야 하기 때문이다.
```

단, 정산/환불은 batch 내부 item 단위로 멱등성을 보장한다.

정산 보상 Idempotency-Key:

```text
MARKET_SETTLEMENT_REWARD:market:{marketId}:prediction:{predictionId}:member:{memberId}
```

무효 환불 Idempotency-Key:

```text
MARKET_REFUND:market:{marketId}:prediction:{predictionId}:member:{memberId}
```

정산/환불은 Prediction 1건이 곧 Member 1명에 대한 포인트 거래 1건이므로, `predictionId` 기준 item별 멱등성 키를 사용한다.

---

### 3-5. 가격 확정 구간은 Market 단위 동시성 제어가 필요하다

예측 참여 확정 시 다음 데이터가 함께 변경된다.

```text
Market total_pool
MarketOption realPoolAmount
MarketOption currentPrice
MarketOption totalContractQuantity
MarketPriceHistory
MarketPrediction
```

Pool-Share 방식의 가격 계산은 선택한 옵션 하나만 보지 않는다.

```text
선택지 가격 = 해당 선택지 pool / 전체 선택지 pool 합
```

따라서 선택한 MarketOption row만 락 잡는 방식은 사용하지 않는다.

잘못된 방식:

```text
사용자 A → YES option row lock
사용자 B → NO option row lock

서로 다른 row라서 동시에 처리 가능
→ 전체 option pool 합을 계산할 때 서로의 변경을 반영하지 못할 수 있음
→ currentPrice 불일치 발생
```

MVP 권장 락 정책:

```sql
SELECT *
FROM market
WHERE id = :marketId
FOR UPDATE;
```

그 다음 해당 Market의 모든 선택지를 고정 순서로 락 조회한다.

```sql
SELECT *
FROM market_option
WHERE market_id = :marketId
ORDER BY id
FOR UPDATE;
```

처리 기준:

```text
1. 같은 Market 안의 가격 확정은 순차 처리한다.
2. Market row를 먼저 락 잡는다.
3. 해당 Market의 모든 MarketOption row를 optionId 오름차순으로 락 잡는다.
4. 선택한 option만 락 잡는 방식은 사용하지 않는다.
5. 고정 순서로 락을 잡아 데드락 가능성을 줄인다.
```

확장안:

```text
Redis Redisson 분산 락
```

단, MVP에서는 DB 비관적 락을 우선 적용한다.

---

### 3-6. 정산 시작은 Atomic Update로 처리한다

```sql
UPDATE market
SET status = 'SETTLEMENT_IN_PROGRESS'
WHERE id = :marketId
AND status IN ('CLOSED', 'DATA_PENDING');
```

처리 기준:

```text
affected row = 1 → 정산 진행
affected row = 0 → 이미 정산 중이거나 정산 가능한 상태가 아님
```

---

### 3-7. 정산이 시작된 Market은 VOIDED 처리할 수 없다

VOIDED 가능 상태:

```text
PENDING
ACTIVE
CLOSED
DATA_PENDING
```

VOIDED 불가능 상태:

```text
SETTLEMENT_IN_PROGRESS
SETTLED
```

정산이 시작된 이후 문제가 발생한 경우 자동 VOIDED 처리하지 않고 관리자 수동 보정 대상으로 남긴다.

---

### 3-8. Scheduler는 chunk 단위로 처리한다

대사, 정산 재시도, 환불 재시도는 한 번에 전체 대상을 처리하지 않는다.

```text
기본 limit = 100
```

처리 기준:

```text
1. 한 트랜잭션에서 최대 limit건만 처리한다.
2. 실패한 건은 다음 Scheduler 주기에 다시 처리한다.
3. 장시간 트랜잭션과 DB 커넥션 고갈을 방지한다.
```

---

### 3-9. Decimal 데이터는 String으로 응답한다

가격, 계약 수량, 포인트 금액, 정산 금액 등 소수점 정밀도가 중요한 값은 JSON Number가 아니라 String으로 응답한다.

대상 예시:

```text
currentPrice
priceSnapshot
contractQuantity
realPoolAmount
virtualPoolAmount
pointAmount
settlementAmount
refundAmount
```

---

### 3-10. Member-Point 연동 referenceType 정책

Market이 Member-Point API를 호출할 때는 다음 값을 전달한다.

```text
referenceType = MARKET_PREDICTION
referenceId = predictionId
```

사용 기준:

| Market 상황 | Member-Point type | referenceType | referenceId |
|---|---|---|---|
| 예측 참여 포인트 차감 | `SPEND_MARKET` | `MARKET_PREDICTION` | predictionId |
| 정산 보상 지급 | `SETTLE_MARKET` | `MARKET_PREDICTION` | predictionId |
| 무효 환불 | `REFUND_MARKET` | `MARKET_PREDICTION` | predictionId |

주의:

```text
referenceType/referenceId는 Member-Point의 point_history에 저장되는 값이다.
Market DB에는 이미 prediction_id가 있으므로 reference_type/reference_id를 중복 저장하지 않는다.
```

---

## 4. 예측 참여 실패 시나리오

### 4-1. 정상 예측 참여

| 항목 | 내용 |
|---|---|
| 발생 시점 | 사용자가 Market 예측 참여 요청 |
| 조건 | Market ACTIVE, 선택지 정상, 중복 참여 아님, 포인트 충분 |
| 처리 | Prediction을 POINT_PENDING으로 저장 후 커밋하고, 트랜잭션 밖에서 포인트 차감 요청 |
| 가격 확정 | 포인트 차감 성공 후 새 트랜잭션에서 Market row와 모든 option row를 락 잡고 가격 확정 |
| 최종 상태 | PredictionStatus = CONFIRMED |
| 재시도 | 필요 없음 |
| 관련 ErrorCode | 없음 |

---

### 4-2. 존재하지 않는 Market에 예측 참여

| 항목 | 내용 |
|---|---|
| 발생 시점 | 예측 참여 요청 |
| 실패 원인 | marketId에 해당하는 Market 없음 |
| 상태 변화 | Prediction 생성 안 함 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_NOT_FOUND |
| HTTP Status | 404 |

---

### 4-3. 예측 참여 가능한 상태가 아닌 Market

| 항목 | 내용 |
|---|---|
| 발생 시점 | 예측 참여 요청 |
| 실패 원인 | MarketStatus가 ACTIVE가 아님 |
| 예시 | PENDING, CLOSED, DATA_PENDING, SETTLED, VOIDED |
| 상태 변화 | Prediction 생성 안 함 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_NOT_ACTIVE 또는 MARKET_CLOSED |
| HTTP Status | 409 |

---

### 4-4. 이미 예측 참여한 사용자가 다시 참여

| 항목 | 내용 |
|---|---|
| 발생 시점 | 예측 참여 요청 |
| 실패 원인 | 동일 marketId, memberId의 Prediction이 이미 존재 |
| 상태 변화 | 새 Prediction 생성 안 함 |
| 포인트 차감 | 수행하지 않음 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_ALREADY_PREDICTED |
| HTTP Status | 409 |

방어 수단:

```sql
UNIQUE (market_id, member_id)
```

---

### 4-5. 존재하지 않는 선택지 선택

| 항목 | 내용 |
|---|---|
| 발생 시점 | 예측 참여 요청 |
| 실패 원인 | marketOptionId가 존재하지 않거나 해당 Market에 속하지 않음 |
| 상태 변화 | Prediction 생성 안 함 |
| 포인트 차감 | 수행하지 않음 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_OPTION_NOT_FOUND |
| HTTP Status | 404 |

---

### 4-6. 예측 참여 포인트 금액 오류

| 항목 | 내용 |
|---|---|
| 발생 시점 | 예측 참여 요청 |
| 실패 원인 | 금액이 0 이하, 최소 금액 미만, 최대 금액 초과, 소수점 정책 위반 |
| 상태 변화 | Prediction 생성 안 함 |
| 포인트 차감 | 수행하지 않음 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_INVALID_BET_AMOUNT |
| HTTP Status | 400 |

---

### 4-7. 가격 확정 동시성 충돌

| 항목 | 내용 |
|---|---|
| 발생 시점 | 포인트 차감 성공 후 가격 확정 트랜잭션 |
| 실패 원인 | Market row 또는 MarketOption row 락 타임아웃, 데드락, 갱신 충돌 |
| 상태 변화 | PredictionStatus = POINT_PENDING 또는 POINT_UNKNOWN 유지 |
| 재시도 | O |
| 복구 방식 | Scheduler가 Member-Point 거래 상태를 확인한 뒤 가격 확정 트랜잭션 재시도 |
| 관련 ErrorCode | MARKET_PRICE_UPDATE_CONFLICT |
| HTTP Status | 409 |

주의:

```text
포인트 차감은 성공했지만 가격 확정 트랜잭션에서 실패할 수 있다.
이 경우 Prediction을 FAILED로 단정하지 않는다.
반드시 Idempotency-Key로 Member-Point 차감 이력을 확인한 뒤,
Market row + 모든 option row 락을 다시 획득하여 가격 확정을 재시도한다.
```

---

## 5. 포인트 차감 실패 시나리오

### 5-1. 포인트 부족

| 항목 | 내용 |
|---|---|
| 발생 시점 | Member-Point 포인트 차감 요청 |
| 실패 원인 | 사용자 보유 포인트 부족 |
| 상태 변화 | PredictionStatus = FAILED |
| 재시도 | X |
| 관련 ErrorCode | POINT_INSUFFICIENT |
| ErrorCode 소유 | Member-Point |
| HTTP Status | 409 |

처리 기준:

```text
POINT_INSUFFICIENT는 요청 형식 오류가 아니라
현재 회원 포인트 잔액 상태와 요청이 충돌한 것이므로 409 Conflict로 처리한다.
```

---

### 5-2. 포인트 차감 요청 타임아웃

| 항목 | 내용 |
|---|---|
| 발생 시점 | Member-Point 포인트 차감 요청 |
| 실패 원인 | 제한 시간 안에 응답을 받지 못함 |
| 위험 | 실제 차감 여부를 알 수 없음 |
| 상태 변화 | PredictionStatus = POINT_UNKNOWN |
| 재시도 | 즉시 재시도 금지 |
| 복구 방식 | Idempotency-Key로 처리 이력 조회 |
| 관련 ErrorCode | EXTERNAL_SERVICE_TIMEOUT |
| HTTP Status | 504 |

---

### 5-3. Member-Point 5xx 응답

| 항목 | 내용 |
|---|---|
| 발생 시점 | Member-Point 포인트 차감 요청 |
| 실패 원인 | Member-Point 내부 서버 오류 |
| 위험 | 포인트 차감 여부가 불명확할 수 있음 |
| 상태 변화 | PredictionStatus = POINT_UNKNOWN |
| 재시도 | O |
| 복구 방식 | Idempotency-Key로 처리 이력 조회 후 재시도 |
| 관련 ErrorCode | EXTERNAL_SERVICE_ERROR |
| HTTP Status | 502 |

---

### 5-4. Member-Point 연결 실패

| 항목 | 내용 |
|---|---|
| 발생 시점 | Member-Point 포인트 차감 요청 |
| 실패 원인 | Connection Refused, 서비스 다운, 네트워크 연결 실패 |
| 상태 변화 | PredictionStatus = POINT_UNKNOWN |
| 재시도 | O |
| 복구 방식 | Scheduler 확인 후 재시도 |
| 관련 ErrorCode | EXTERNAL_SERVICE_UNAVAILABLE |
| HTTP Status | 503 |

---

### 5-5. POINT_PENDING 상태 고착

| 항목 | 내용 |
|---|---|
| 발생 시점 | Prediction POINT_PENDING 저장 이후 |
| 실패 원인 | Market 서버 장애, DB 업데이트 실패, 가격 확정 트랜잭션 실패, 인스턴스 종료 |
| 상태 변화 | PredictionStatus = POINT_PENDING 상태로 고착 |
| 재시도 | O |
| 복구 방식 | Scheduler가 3분 이상 지난 POINT_PENDING을 조회하여 대사 |
| 관련 ErrorCode | 없음 또는 내부 로그 |
| HTTP Status | API 응답 없음 |

대표 사례:

```text
Prediction POINT_PENDING 저장
→ Member-Point 포인트 차감 성공
→ 가격 확정 트랜잭션 시작 전 또는 진행 중 Market 서버 장애
→ PredictionStatus = POINT_PENDING 상태로 고착
```

대사 방식:

```text
1. Prediction의 point_spend_idempotency_key로 Member-Point 거래 상태 조회
2. 차감 성공 확인 시 Market row + 모든 option row 락 획득
3. priceSnapshot, contractQuantity 확정
4. pool 갱신, 가격 재계산, PriceHistory 저장
5. Prediction CONFIRMED 변경
```

---

## 6. 선택지 검증 실패 시나리오

### 6-1. 선택지 범위가 겹치는 경우

| 항목 | 내용 |
|---|---|
| 발생 시점 | Market 생성 또는 승인 |
| 실패 원인 | 두 개 이상의 선택지 범위가 겹침 |
| 문제 | 실제 값이 두 선택지에 모두 속할 수 있음 |
| 상태 변화 | Market 생성 또는 승인 실패 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_INVALID_OPTION_RANGE |
| HTTP Status | 400 |

---

### 6-2. 선택지 범위 사이에 빈 구간이 있는 경우

| 항목 | 내용 |
|---|---|
| 발생 시점 | Market 생성 또는 승인 |
| 실패 원인 | 어떤 선택지에도 속하지 않는 값의 범위가 존재 |
| 문제 | 실제 값이 어느 선택지에도 속하지 않을 수 있음 |
| 상태 변화 | Market 생성 또는 승인 실패 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_INVALID_OPTION_RANGE |
| HTTP Status | 400 |

---

### 6-3. 정답 선택지를 찾을 수 없는 경우

| 항목 | 내용 |
|---|---|
| 발생 시점 | 정산 |
| 실패 원인 | 실제 데이터 값과 매칭되는 선택지가 없음 |
| 상태 변화 | MarketStatus = DATA_PENDING 유지 |
| 재시도 | X |
| 복구 방식 | 관리자 확인 |
| 관련 ErrorCode | MARKET_WINNING_OPTION_NOT_FOUND |
| HTTP Status | 409 |

---

## 7. 공공 데이터 수집 실패 시나리오

### 7-1. 공공 데이터가 아직 수집되지 않은 경우

| 항목 | 내용 |
|---|---|
| 발생 시점 | 정산 데이터 조회 |
| 실패 원인 | 예상 수집일이 되었지만 데이터가 아직 없음 |
| 상태 변화 | MarketStatus = DATA_PENDING 유지 |
| 대기 기간 | 예상 수집일로부터 최대 3일 |
| 재시도 | O |
| 관련 ErrorCode | MARKET_SETTLEMENT_DATA_NOT_FOUND |
| HTTP Status | 409 |

---

### 7-2. 공공 데이터 수집 API 실패

| 항목 | 내용 |
|---|---|
| 발생 시점 | 외부 공공 데이터 API 호출 |
| 실패 원인 | 외부 API 장애, 타임아웃, 비정상 응답 |
| 상태 변화 | MarketStatus = DATA_PENDING 유지 |
| 재시도 | O |
| 관련 ErrorCode | MARKET_DATA_FETCH_FAILED 또는 EXTERNAL_SERVICE_TIMEOUT |
| HTTP Status | 500 또는 504 |

---

### 7-3. 정산 데이터 값이 비정상인 경우

| 항목 | 내용 |
|---|---|
| 발생 시점 | 정산 데이터 검증 |
| 실패 원인 | null, 비정상 범위, 계산 불가 값 |
| 상태 변화 | MarketStatus = DATA_PENDING 유지 |
| 재시도 | △ |
| 복구 방식 | 관리자 확인 |
| 관련 ErrorCode | MARKET_INVALID_SETTLEMENT_DATA |
| HTTP Status | 409 |

---

## 8. 정산 실패 시나리오

### 8-1. 정상 정산

| 항목 | 내용 |
|---|---|
| 발생 시점 | Market 정산 |
| 조건 | 정산 데이터 존재, 정답 선택지 계산 성공, 포인트 지급 모두 성공 |
| 상태 변화 | MarketStatus = SETTLED, PredictionStatus = SETTLED |
| 재시도 | 필요 없음 |
| 관련 ErrorCode | 없음 |

---

### 8-2. 정산 스케줄러 중복 실행

| 항목 | 내용 |
|---|---|
| 발생 시점 | 정산 Scheduler |
| 실패 원인 | 같은 Market 정산이 동시에 여러 번 실행됨 |
| 방어 수단 | DB Atomic Update |
| 상태 변화 | 먼저 성공한 프로세스만 SETTLEMENT_IN_PROGRESS로 변경 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_INVALID_STATUS 또는 내부 로그 |
| HTTP Status | 409 또는 없음 |

---

### 8-3. 이미 정산된 Market 재정산 시도

| 항목 | 내용 |
|---|---|
| 발생 시점 | 정산 요청 |
| 실패 원인 | MarketStatus = SETTLED |
| 상태 변화 | 없음 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_ALREADY_SETTLED |
| HTTP Status | 409 |

---

### 8-4. 정산 가능한 상태가 아닌 Market

| 항목 | 내용 |
|---|---|
| 발생 시점 | 정산 요청 |
| 실패 원인 | CLOSED, DATA_PENDING이 아닌 상태 |
| 예시 | PENDING, ACTIVE, SETTLEMENT_IN_PROGRESS, SETTLED, VOIDED |
| 상태 변화 | 없음 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_INVALID_STATUS |
| HTTP Status | 409 |

---

### 8-5. 일부 유저 정산 지급 실패

| 항목 | 내용 |
|---|---|
| 발생 시점 | Member-Point 정산 보상 batch API 호출 |
| 실패 원인 | 일부 item의 지급 요청 실패 |
| 상태 변화 | MarketStatus = SETTLEMENT_IN_PROGRESS 유지 |
| 성공 건 | PredictionStatus = SETTLED, market_settlement_detail.status = SUCCESS |
| 실패 건 | market_settlement_detail.status = FAILED 또는 UNKNOWN |
| 재시도 | O |
| 관련 ErrorCode | MARKET_SETTLEMENT_FAILED 또는 EXTERNAL_SERVICE_ERROR |
| HTTP Status | 500 또는 502 |

정산 batch API는 유지하되, 멱등성은 item 단위로 보장한다.

정산 item Idempotency-Key:

```text
MARKET_SETTLEMENT_REWARD:market:{marketId}:prediction:{predictionId}:member:{memberId}
```

Member-Point 응답의 item별 `results[]` 처리 기준:

| status | Market 처리 |
|---|---|
| `PROCESSED` | 성공으로 처리 |
| `ALREADY_PROCESSED` | 이미 처리된 거래이므로 성공으로 처리 |
| `FAILED` | 실패 건으로 기록하고 다음 Scheduler 주기에 재시도 |

---

### 8-6. 정산 대상 예측이 없는 경우

| 항목 | 내용 |
|---|---|
| 발생 시점 | 정산 |
| 실패 원인 | CONFIRMED 상태의 Prediction이 없음 |
| 상태 변화 | 관리자 확인 또는 VOIDED |
| 재시도 | X |
| 관련 ErrorCode | MARKET_NO_PREDICTIONS |
| HTTP Status | 409 |

---

## 9. 환불 실패 시나리오

### 9-1. 정상 환불

| 항목 | 내용 |
|---|---|
| 발생 시점 | Market VOIDED 처리 |
| 조건 | 환불 대상 Prediction 존재, 환불 요청 성공 |
| 상태 변화 | PredictionStatus = REFUNDED |
| 재시도 | 필요 없음 |
| 관련 ErrorCode | 없음 |

---

### 9-2. VOIDED 처리 불가능한 Market

| 항목 | 내용 |
|---|---|
| 발생 시점 | 관리자 VOIDED 처리 요청 |
| 실패 원인 | MarketStatus가 SETTLEMENT_IN_PROGRESS 또는 SETTLED |
| 상태 변화 | 없음 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_CANNOT_VOID |
| HTTP Status | 409 |

---

### 9-3. 환불 대상이 아닌 Prediction 환불 시도

| 항목 | 내용 |
|---|---|
| 발생 시점 | 환불 요청 |
| 실패 원인 | FAILED, SETTLED, REFUNDED 등 환불 대상이 아닌 상태 |
| 상태 변화 | 없음 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_REFUND_NOT_ALLOWED |
| HTTP Status | 409 |

---

### 9-4. 이미 환불된 Prediction 재환불 시도

| 항목 | 내용 |
|---|---|
| 발생 시점 | 환불 요청 |
| 실패 원인 | PredictionStatus = REFUNDED |
| 상태 변화 | 없음 |
| 재시도 | X |
| 관련 ErrorCode | MARKET_ALREADY_REFUNDED |
| HTTP Status | 409 |

---

### 9-5. 환불 요청 타임아웃

| 항목 | 내용 |
|---|---|
| 발생 시점 | Member-Point 환불 요청 |
| 실패 원인 | 제한 시간 안에 응답 없음 |
| 위험 | 실제 환불 여부를 알 수 없음 |
| 상태 변화 | PredictionStatus = REFUND_UNKNOWN |
| 재시도 | 즉시 재시도 금지 |
| 복구 방식 | Idempotency-Key로 처리 이력 조회 |
| 관련 ErrorCode | EXTERNAL_SERVICE_TIMEOUT |
| HTTP Status | 504 |

---

### 9-6. 일부 유저 환불 실패

| 항목 | 내용 |
|---|---|
| 발생 시점 | Member-Point 환불 batch API 호출 |
| 실패 원인 | 일부 item의 환불 요청 실패 |
| 상태 변화 | MarketStatus = VOIDED 유지 |
| 성공 건 | PredictionStatus = REFUNDED, market_refund_detail.status = SUCCESS |
| 실패 건 | market_refund_detail.status = FAILED 또는 UNKNOWN |
| 재시도 | O |
| 관련 ErrorCode | MARKET_REFUND_FAILED 또는 EXTERNAL_SERVICE_ERROR |
| HTTP Status | 500 또는 502 |

환불 batch API는 유지하되, 멱등성은 item 단위로 보장한다.

환불 item Idempotency-Key:

```text
MARKET_REFUND:market:{marketId}:prediction:{predictionId}:member:{memberId}
```

Member-Point 응답의 item별 `results[]` 처리 기준:

| status | Market 처리 |
|---|---|
| `PROCESSED` | 성공으로 처리 |
| `ALREADY_PROCESSED` | 이미 처리된 거래이므로 성공으로 처리 |
| `FAILED` | 실패 건으로 기록하고 다음 Scheduler 주기에 재시도 |

---

## 10. 동시성/중복 요청 시나리오

### 10-1. 같은 사용자가 동시에 서로 다른 선택지 선택

| 항목 | 내용 |
|---|---|
| 발생 시점 | 예측 참여 요청 |
| 예시 | YES 요청과 NO 요청이 동시에 들어옴 |
| 방어 수단 | UNIQUE (market_id, member_id), Idempotency-Key |
| 성공 기준 | 먼저 처리된 요청 1건만 성공 |
| 실패 요청 | MARKET_ALREADY_PREDICTED 또는 IDEMPOTENCY_KEY_CONFLICT |
| 포인트 차감 | 1회만 수행되어야 함 |

---

### 10-2. 브라우저 더블 클릭 또는 프론트 재시도

| 항목 | 내용 |
|---|---|
| 발생 시점 | 예측 참여 요청 |
| 실패 원인 | 동일 요청 중복 전송 |
| 방어 수단 | Idempotency-Key |
| 처리 | 기존 처리 결과 조회 |
| 포인트 차감 | 1회만 수행 |
| 관련 ErrorCode | 없음 또는 IDEMPOTENCY_KEY_CONFLICT |

---

### 10-3. 정산 스케줄러 중복 실행

| 항목 | 내용 |
|---|---|
| 발생 시점 | 정산 Scheduler |
| 실패 원인 | 같은 Market 정산이 동시에 여러 번 실행됨 |
| 방어 수단 | Atomic Update |
| 처리 | 한 프로세스만 SETTLEMENT_IN_PROGRESS 획득 |
| 실패 요청 | 중단 |
| 포인트 지급 | 중복 지급 방지 필요 |

---

## 11. Client 후속 처리 시나리오

### 11-1. 예측 참여 중 502/503/504 수신

| 항목 | 내용 |
|---|---|
| 발생 시점 | 예측 참여 API 호출 |
| 실패 원인 | 포인트 서비스 타임아웃, 연결 실패, 5xx |
| 프론트 처리 | 실패 화면 표시 금지 |
| 안내 문구 | 예측 참여 처리 상태를 확인 중입니다 |
| 후속 처리 | 3~5초 간격으로 내 예측 상태 조회 API polling |
| 조회 API | `GET /api/v1/markets/{marketId}/predictions/me` |

Polling 결과:

| PredictionStatus | Client 처리 |
|---|---|
| `CONFIRMED` | 성공 화면 표시 |
| `FAILED` | 실패 메시지 표시 |
| `POINT_PENDING` | 계속 확인 중 |
| `POINT_UNKNOWN` | 계속 확인 중 |

---

## 12. 내부 Scheduler Chunk 처리 시나리오

### 12-1. 대사 대상이 수천 건인 경우

| 항목 | 내용 |
|---|---|
| 발생 시점 | 내부 Scheduler 실행 |
| 위험 | 긴 트랜잭션, DB 커넥션 고갈, API 타임아웃 |
| 처리 | limit 단위 chunk 처리 |
| 기본값 | limit = 100 |
| 대상 API | 대사, 정산 재시도, 환불 재시도, 공공 데이터 수집 재시도 |

예시:

```http
POST /internal/api/v1/markets/predictions/reconcile-point?limit=100
POST /internal/api/v1/markets/settlements/retry-failed?limit=100
POST /internal/api/v1/markets/refunds/retry-failed?limit=100
```

---

## 13. 관리자 확인 대상

| 상황 | 상태 |
|---|---|
| 예상 수집일로부터 3일이 지나도 공공 데이터 없음 | DATA_PENDING |
| 정산 데이터 값이 비정상 | DATA_PENDING |
| 정답 선택지를 계산할 수 없음 | DATA_PENDING |
| 정산 대상이 비정상적으로 없음 | CLOSED 또는 DATA_PENDING |
| 반복 재시도 후에도 정산 지급 실패 | SETTLEMENT_IN_PROGRESS |
| 반복 재시도 후에도 환불 실패 | VOIDED |
| SETTLEMENT_IN_PROGRESS 이후 정산 오류 발생 | SETTLEMENT_IN_PROGRESS |

단, `SETTLEMENT_IN_PROGRESS` 또는 `SETTLED` 상태의 Market은 VOIDED 처리할 수 없다.

---

## 14. Retry 정책 요약

### 14-1. 재시도 대상

| 상황 | Retry | 이유 |
|---|---:|---|
| Member-Point 타임아웃 | O | 일시적 네트워크 장애 가능 |
| Member-Point 5xx | O | 일시적 서버 장애 가능 |
| Member-Point 연결 실패 | O | 서비스 재기동 후 성공 가능 |
| 3분 이상 POINT_PENDING 고착 | O | Market 서버 장애 또는 상태 업데이트 실패 가능 |
| 가격 확정 동시성 충돌 | O | 일시적 락 경합 가능 |
| 공공 데이터 API 실패 | O | 외부 API 일시 장애 가능 |
| 정산 포인트 지급 실패 | O | 일부 실패 건 재처리 가능 |
| 환불 실패 | O | 일부 실패 건 재처리 가능 |

---

### 14-2. 재시도 금지 대상

| 상황 | Retry | 이유 |
|---|---:|---|
| Market 없음 | X | 요청 대상이 잘못됨 |
| Market 마감 | X | 비즈니스 조건 불충족 |
| 이미 예측 참여함 | X | 중복 요청 |
| 선택지 없음 | X | 요청 값 오류 |
| 예측 금액 오류 | X | 요청 값 오류 |
| 포인트 부족 | X | 재시도해도 성공 불가 |
| 선택지 범위 오류 | X | 관리자 수정 필요 |
| 이미 정산 완료 | X | 중복 정산 방지 |
| 이미 환불 완료 | X | 중복 환불 방지 |
| SETTLEMENT_IN_PROGRESS 또는 SETTLED 상태의 VOIDED 시도 | X | 비즈니스 락 위반 |

---

## 15. 최종 완료 기준

- [ ] 예측 참여 시 Prediction을 먼저 `POINT_PENDING`으로 저장하고 커밋한다.
- [ ] DB 락을 잡은 상태로 Member-Point HTTP API를 호출하지 않는다.
- [ ] 포인트 차감 성공 후 가격 확정 트랜잭션까지 완료되면 `CONFIRMED`로 변경한다.
- [ ] 포인트 부족은 `FAILED`로 변경한다.
- [ ] 포인트 차감 타임아웃은 `POINT_UNKNOWN`으로 변경한다.
- [ ] `POINT_UNKNOWN`은 Scheduler가 Idempotency-Key로 처리 이력을 조회한다.
- [ ] 3분 이상 고착된 `POINT_PENDING`도 Scheduler 대사 대상에 포함한다.
- [ ] 하나의 Market에 대해 한 사용자는 하나의 Prediction만 가질 수 있다.
- [ ] `UNIQUE (market_id, member_id)` 제약을 둔다.
- [ ] 선택지 범위 겹침/공백은 생성 또는 승인 단계에서 차단한다.
- [ ] 가격 확정 트랜잭션은 Market row와 해당 Market의 모든 MarketOption row를 고정 순서로 비관적 락 조회한다.
- [ ] Decimal 필드는 JSON String으로 응답한다.
- [ ] 가격 이력 조회는 페이징한다.
- [ ] Client는 502/503/504 수신 시 polling으로 최종 상태를 확인한다.
- [ ] 공공 데이터는 예상 수집일로부터 최대 3일간 `DATA_PENDING`으로 유지한다.
- [ ] 정산 시작은 Atomic Update로 `SETTLEMENT_IN_PROGRESS` 권한을 획득한다.
- [ ] 일부 정산 실패 시 `SETTLEMENT_IN_PROGRESS`를 유지한다.
- [ ] 정산은 item별 idempotencyKey를 사용하고 실패한 정산 지급 건만 재시도한다.
- [ ] `SETTLEMENT_IN_PROGRESS`, `SETTLED` 상태는 VOIDED 처리할 수 없다.
- [ ] 환불은 item별 idempotencyKey를 사용하고 환불 타임아웃은 `REFUND_UNKNOWN`으로 변경한다.
- [ ] Scheduler는 `limit` 기반 chunk 처리를 한다.
