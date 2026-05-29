# MARKET_ERROR_CODE.md

> Market 서비스에서 발생할 수 있는 도메인 비즈니스 에러와 실패 처리 정책을 정의한다.  
> 공통 요청 오류, 인증/인가 오류, 서버 내부 오류, 서비스 간 통신 오류는 루트 `ERROR_POLICY.md`의 공통 ErrorCode를 따른다.  
> 이 문서는 Market 도메인 고유의 상태 충돌, 예측 참여 실패, 선택지 오류, 가격 갱신 충돌, 정산 실패, 환불 실패를 다룬다.

---

## 1. 기본 원칙

Market 서비스의 에러 처리는 다음 원칙을 따른다.

1. 모든 에러 응답은 공통 `ApiResponse<T>` 형식을 따른다.
2. 공통 에러로 표현 가능한 경우 Market 전용 ErrorCode를 새로 만들지 않는다.
3. Market 도메인 상태와 직접 관련된 에러만 `MARKET_` prefix를 사용한다.
4. 포인트 차감, 정산 지급, 환불처럼 Member-Point 서비스와 연동되는 작업은 멱등성 키를 반드시 사용한다.
5. 타임아웃은 실패가 아니라 처리 여부 불명확 상태로 본다.
6. 실패 시 단순히 에러 응답만 반환하지 않고, Market 또는 Prediction의 상태 변화까지 명확히 정의한다.
7. 정산이 시작된 Market은 VOIDED 처리할 수 없다.
8. 정산 시작은 Atomic Update로 처리한다.
9. 가격 갱신 구간은 DB 비관적 락 또는 Atomic Update로 보호한다.
10. Decimal 필드는 API 응답에서 String으로 내려준다.

---

## 2. Market 상태 정책

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
    PENDING,          // 예측 생성됨, 포인트 차감 전
    POINT_PENDING,    // 포인트 차감 요청 중
    CONFIRMED,        // 포인트 차감 완료, 예측 참여 확정
    FAILED,           // 예측 참여 실패
    POINT_UNKNOWN,    // 포인트 차감 여부 불명확
    SETTLED,          // 정산 완료
    REFUND_PENDING,   // 환불 요청 중
    REFUND_UNKNOWN,   // 환불 여부 불명확
    REFUNDED          // 환불 완료
}
```

---

## 3. 예측 참여 처리 흐름

```text
1. Market 조회
2. Market 상태 검증
3. 선택지 검증
4. 중복 참여 검증
5. Prediction을 POINT_PENDING 상태로 먼저 저장
6. MarketOption 가격 갱신 구간에 비관적 락 또는 Atomic Update 적용
7. Member-Point 서비스에 포인트 차감 요청
8. 포인트 차감 성공 시 Prediction CONFIRMED
9. 명확한 실패 시 Prediction FAILED
10. 타임아웃, 5xx, 응답 불명확 시 Prediction POINT_UNKNOWN
11. Scheduler가 Idempotency-Key로 Member-Point 처리 이력을 조회하여 상태 보정
12. 3분 이상 POINT_PENDING에 머무른 Prediction도 Scheduler 대사 대상으로 포함
```

---

## 4. 멱등성 키 정책

| 작업 | Idempotency-Key |
|---|---|
| 예측 참여 포인트 차감 | `MARKET_PREDICTION_SPEND:market:{marketId}:member:{memberId}` |
| 정산 보상 지급 | `MARKET_SETTLEMENT_REWARD:market:{marketId}:prediction:{predictionId}:member:{memberId}` |
| 무효 처리 환불 | `MARKET_REFUND:market:{marketId}:prediction:{predictionId}:member:{memberId}` |

예측 참여 포인트 차감 키에는 `optionId`를 포함하지 않는다.

DB 제약 조건:

```sql
UNIQUE (market_id, member_id)
```

---

## 5. Decimal 응답 정책

Market 서비스에서 소수점 정밀도가 중요한 값은 JSON Number가 아니라 String으로 응답한다.

대상 필드:

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

예시:

```json
{
  "currentPrice": "0.18342111",
  "contractQuantity": "12.50000000",
  "pointAmount": "100.00"
}
```

---

## 6. 예측 참여 관련 ErrorCode

| ErrorCode | HTTP Status | 설명 | Retry | 상태 변화 |
|---|---:|---|---:|---|
| `MARKET_NOT_FOUND` | 404 | Market을 찾을 수 없음 | X | 없음 |
| `MARKET_NOT_ACTIVE` | 409 | Market이 예측 참여 가능한 상태가 아님 | X | Prediction 생성 안 함 |
| `MARKET_CLOSED` | 409 | 이미 마감된 Market | X | Prediction 생성 안 함 |
| `MARKET_ALREADY_PREDICTED` | 409 | 사용자가 이미 해당 Market에 예측 참여함 | X | Prediction 생성 안 함 |
| `MARKET_OPTION_NOT_FOUND` | 404 | 선택지를 찾을 수 없음 | X | Prediction 생성 안 함 |
| `MARKET_INVALID_BET_AMOUNT` | 400 | 예측 참여 포인트 금액이 유효하지 않음 | X | Prediction 생성 안 함 |
| `MARKET_PRICE_UPDATE_CONFLICT` | 409 | 가격 갱신 중 동시성 충돌 발생 | O | 트랜잭션 롤백 또는 상태 대사 |

---

## 7. 선택지 검증 관련 ErrorCode

| ErrorCode | HTTP Status | 설명 | Retry | 상태 변화 |
|---|---:|---|---:|---|
| `MARKET_INVALID_OPTION` | 400 | 선택지 구성이 유효하지 않음 | X | Market 생성/승인 실패 |
| `MARKET_INVALID_OPTION_RANGE` | 400 | 선택지 범위가 유효하지 않음 | X | Market 생성/승인 실패 |
| `MARKET_WINNING_OPTION_NOT_FOUND` | 409 | 정산 결과와 매칭되는 정답 선택지를 찾을 수 없음 | X | 관리자 확인 대상 |

선택지 검증 규칙:

```text
1. 선택지는 최소 2개 이상이어야 한다.
2. 하나의 사용자는 여러 선택지 중 정확히 1개만 선택할 수 있다.
3. 선택지 범위가 서로 겹치면 안 된다.
4. 선택지 범위 사이에 빈 구간이 있으면 안 된다.
5. 경계값 포함 여부가 명확해야 한다.
6. 실제 정산 값은 반드시 하나의 선택지에만 매칭되어야 한다.
```

---

## 8. 포인트 차감 연동 실패 시나리오

### 8-1. 포인트 부족

```text
Member-Point → POINT_INSUFFICIENT 반환
```

처리:

```text
PredictionStatus = FAILED
Retry = X
```

`POINT_INSUFFICIENT`는 Member-Point 도메인 ErrorCode이므로 Market 전용 ErrorCode를 새로 만들지 않는다.

### 8-2. 포인트 차감 요청 타임아웃

처리:

```text
PredictionStatus = POINT_UNKNOWN
Retry = 즉시 재시도하지 않음
보정 방식 = Idempotency-Key로 Member-Point 처리 이력 조회
```

사용 ErrorCode:

```text
EXTERNAL_SERVICE_TIMEOUT
```

### 8-3. Member-Point 5xx 응답

처리:

```text
PredictionStatus = POINT_UNKNOWN
Retry = O
보정 방식 = Idempotency-Key로 Member-Point 처리 이력 조회
```

사용 ErrorCode:

```text
EXTERNAL_SERVICE_ERROR
```

### 8-4. Member-Point 연결 실패

처리:

```text
PredictionStatus = POINT_UNKNOWN
Retry = O
보정 방식 = Idempotency-Key로 Member-Point 처리 이력 조회
```

사용 ErrorCode:

```text
EXTERNAL_SERVICE_UNAVAILABLE
```

### 8-5. POINT_PENDING 고착

처리:

```text
updatedAt 기준 3분 이상 지난 POINT_PENDING을 Scheduler 대사 대상으로 포함
Idempotency-Key로 Member-Point 처리 이력 조회
```

상태 전이:

```text
차감 성공 확인 → CONFIRMED
차감 실패 확인 → FAILED
처리 이력 없음 → 재시도 또는 FAILED
```

---

## 9. 공공 데이터 수집 실패 시나리오

| ErrorCode | HTTP Status | 설명 | Retry | 상태 변화 |
|---|---:|---|---:|---|
| `MARKET_SETTLEMENT_DATA_NOT_FOUND` | 409 | 정산에 필요한 데이터가 아직 없음 | △ | `DATA_PENDING` 유지 |
| `MARKET_INVALID_SETTLEMENT_DATA` | 409 | 정산 데이터 값이 비정상 | △ | 관리자 확인 대상 |
| `MARKET_DATA_FETCH_FAILED` | 500 | 정산 데이터 수집 실패 | O | `DATA_PENDING` 유지 |

데이터 대기 정책:

```text
예상 수집일로부터 최대 3일간 DATA_PENDING 상태로 유지한다.
3일 이내 데이터 수집 성공 시 정산을 진행한다.
3일 경과 후에도 데이터가 없으면 관리자 확인 대상으로 전환한다.
관리자 판단에 따라 Market을 VOIDED 처리할 수 있다.
단, SETTLEMENT_IN_PROGRESS 또는 SETTLED 상태는 VOIDED 처리할 수 없다.
```

---

## 10. 정산 실패 시나리오

### 10-1. 정산 처리 흐름

```text
1. CLOSED 또는 DATA_PENDING 상태의 Market을 정산 대상으로 조회
2. DB Atomic Update로 SETTLEMENT_IN_PROGRESS 획득
3. 정산 데이터 확인
4. 정답 선택지 계산
5. 정산 대상 Prediction 조회
6. Member-Point에 정산 보상 지급 요청
7. 성공한 Prediction은 SETTLED
8. 실패한 Prediction은 재시도 대상으로 기록
9. 모든 지급 성공 시 MarketStatus = SETTLED
10. 일부 실패 시 MarketStatus = SETTLEMENT_IN_PROGRESS 유지
```

### 10-2. 정산 시작 Atomic Update

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

### 10-3. 정산 관련 ErrorCode

| ErrorCode | HTTP Status | 설명 | Retry | 상태 변화 |
|---|---:|---|---:|---|
| `MARKET_INVALID_STATUS` | 409 | 현재 상태에서 요청한 작업을 수행할 수 없음 | X | 없음 |
| `MARKET_ALREADY_SETTLED` | 409 | 이미 정산 완료된 Market | X | 없음 |
| `MARKET_NO_PREDICTIONS` | 409 | 정산할 예측 참여자가 없음 | X | 관리자 확인 또는 VOIDED |
| `MARKET_INVALID_SETTLEMENT` | 409 | 정산 조건을 충족하지 않음 | X | 기존 상태 유지 |
| `MARKET_SETTLEMENT_FAILED` | 500 | 정산 처리 중 실패 발생 | O | `SETTLEMENT_IN_PROGRESS` 유지 |

---

## 11. 환불 및 VOIDED 처리 실패 시나리오

### 11-1. VOIDED 처리 가능 상태

```text
PENDING
ACTIVE
CLOSED
DATA_PENDING
```

### 11-2. VOIDED 처리 불가능 상태

```text
SETTLEMENT_IN_PROGRESS
SETTLED
```

정산이 시작된 Market은 관리자도 VOIDED 처리할 수 없다.

### 11-3. 환불 관련 ErrorCode

| ErrorCode | HTTP Status | 설명 | Retry | 상태 변화 |
|---|---:|---|---:|---|
| `MARKET_CANNOT_VOID` | 409 | 현재 상태에서는 Market을 무효 처리할 수 없음 | X | 없음 |
| `MARKET_REFUND_NOT_ALLOWED` | 409 | 환불 대상이 아닌 Prediction 환불 시도 | X | 없음 |
| `MARKET_ALREADY_REFUNDED` | 409 | 이미 환불 완료된 Prediction | X | 없음 |
| `MARKET_REFUND_FAILED` | 500 | 환불 처리 실패 | O | `REFUND_UNKNOWN` 또는 재시도 대상 |

`MARKET_CANNOT_VOID`는 특히 다음 상황에서 사용한다.

```text
MarketStatus = SETTLEMENT_IN_PROGRESS
MarketStatus = SETTLED
```

---

## 12. 내부 Scheduler Chunk 처리 정책

내부 Scheduler API는 한 번에 전체 대상을 처리하지 않는다.

대상 API:

```http
POST /internal/api/v1/markets/predictions/reconcile-point?limit=100
POST /internal/api/v1/markets/settlements/retry-failed?limit=100
POST /internal/api/v1/markets/refunds/retry-failed?limit=100
POST /internal/api/v1/markets/settlement-data/retry-fetch?limit=100
```

처리 기준:

```text
1. limit 기본값은 100이다.
2. 한 번의 실행에서 최대 limit건만 처리한다.
3. 실패한 건은 다음 Scheduler 주기에 다시 처리한다.
4. 긴 트랜잭션과 DB 커넥션 고갈을 방지한다.
```

---

## 13. MarketErrorCode Enum 예시

```java
@Getter
@RequiredArgsConstructor
public enum MarketErrorCode implements ErrorCode {

    MARKET_NOT_FOUND("MARKET_NOT_FOUND", "Market을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    MARKET_NOT_ACTIVE("MARKET_NOT_ACTIVE", "예측 참여 가능한 상태의 Market이 아닙니다.", HttpStatus.CONFLICT),
    MARKET_CLOSED("MARKET_CLOSED", "이미 마감된 Market입니다.", HttpStatus.CONFLICT),
    MARKET_ALREADY_PREDICTED("MARKET_ALREADY_PREDICTED", "이미 예측 참여한 Market입니다.", HttpStatus.CONFLICT),
    MARKET_OPTION_NOT_FOUND("MARKET_OPTION_NOT_FOUND", "Market 선택지를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    MARKET_INVALID_BET_AMOUNT("MARKET_INVALID_BET_AMOUNT", "예측 참여 포인트 금액이 유효하지 않습니다.", HttpStatus.BAD_REQUEST),
    MARKET_PRICE_UPDATE_CONFLICT("MARKET_PRICE_UPDATE_CONFLICT", "Market 가격 갱신 중 동시성 충돌이 발생했습니다.", HttpStatus.CONFLICT),

    MARKET_INVALID_OPTION("MARKET_INVALID_OPTION", "Market 선택지 구성이 유효하지 않습니다.", HttpStatus.BAD_REQUEST),
    MARKET_INVALID_OPTION_RANGE("MARKET_INVALID_OPTION_RANGE", "Market 선택지 범위가 유효하지 않습니다.", HttpStatus.BAD_REQUEST),
    MARKET_WINNING_OPTION_NOT_FOUND("MARKET_WINNING_OPTION_NOT_FOUND", "정산 결과와 매칭되는 정답 선택지를 찾을 수 없습니다.", HttpStatus.CONFLICT),

    MARKET_SETTLEMENT_DATA_NOT_FOUND("MARKET_SETTLEMENT_DATA_NOT_FOUND", "정산에 필요한 데이터를 찾을 수 없습니다.", HttpStatus.CONFLICT),
    MARKET_INVALID_SETTLEMENT_DATA("MARKET_INVALID_SETTLEMENT_DATA", "정산 데이터가 유효하지 않습니다.", HttpStatus.CONFLICT),
    MARKET_DATA_FETCH_FAILED("MARKET_DATA_FETCH_FAILED", "정산 데이터 수집에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),

    MARKET_INVALID_STATUS("MARKET_INVALID_STATUS", "현재 Market 상태에서는 요청한 작업을 수행할 수 없습니다.", HttpStatus.CONFLICT),
    MARKET_ALREADY_SETTLED("MARKET_ALREADY_SETTLED", "이미 정산 완료된 Market입니다.", HttpStatus.CONFLICT),
    MARKET_NO_PREDICTIONS("MARKET_NO_PREDICTIONS", "정산할 예측 참여자가 없습니다.", HttpStatus.CONFLICT),
    MARKET_INVALID_SETTLEMENT("MARKET_INVALID_SETTLEMENT", "정산 조건을 충족하지 않습니다.", HttpStatus.CONFLICT),
    MARKET_SETTLEMENT_FAILED("MARKET_SETTLEMENT_FAILED", "정산 처리에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),

    MARKET_REFUND_NOT_ALLOWED("MARKET_REFUND_NOT_ALLOWED", "환불 대상이 아닌 Prediction입니다.", HttpStatus.CONFLICT),
    MARKET_ALREADY_REFUNDED("MARKET_ALREADY_REFUNDED", "이미 환불 완료된 Prediction입니다.", HttpStatus.CONFLICT),
    MARKET_REFUND_FAILED("MARKET_REFUND_FAILED", "환불 처리에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),

    MARKET_CANNOT_UPDATE_ACTIVE("MARKET_CANNOT_UPDATE_ACTIVE", "진행 중인 Market의 핵심 조건은 수정할 수 없습니다.", HttpStatus.CONFLICT),
    MARKET_ALREADY_CLOSED("MARKET_ALREADY_CLOSED", "이미 마감된 Market입니다.", HttpStatus.CONFLICT),
    MARKET_CANNOT_VOID("MARKET_CANNOT_VOID", "현재 상태에서는 Market을 무효 처리할 수 없습니다.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
```

---

## 14. Market에서 사용하는 공통 ErrorCode

| ErrorCode | 사용 상황 |
|---|---|
| `VALIDATION_FAILED` | 요청 값 검증 실패 |
| `FORBIDDEN` | 관리자 권한이 필요한 작업 |
| `IDEMPOTENCY_KEY_REQUIRED` | 포인트 차감/정산/환불 API 호출 시 멱등성 키 누락 |
| `IDEMPOTENCY_KEY_CONFLICT` | 동일 멱등성 키로 다른 요청 발생 |
| `EXTERNAL_SERVICE_TIMEOUT` | Member-Point 또는 외부 데이터 API 타임아웃 |
| `EXTERNAL_SERVICE_UNAVAILABLE` | Member-Point 또는 외부 데이터 API 연결 실패 |
| `EXTERNAL_SERVICE_ERROR` | 상대 서비스 5xx 응답 |
| `DATABASE_ERROR` | DB 처리 실패 |
| `INTERNAL_ERROR` | 예상하지 못한 서버 내부 오류 |

---

## 15. API_SPEC.md 작성 시 표기 방식

Market API 명세서에는 각 API 하단에 발생 가능한 ErrorCode만 요약해서 적는다.

예시:

```md
### POST /api/v1/markets/{marketId}/predictions

#### 발생 가능한 ErrorCode

| ErrorCode | HTTP Status | 설명 |
|---|---:|---|
| MARKET_NOT_FOUND | 404 | Market 없음 |
| MARKET_NOT_ACTIVE | 409 | 참여 가능한 상태가 아님 |
| MARKET_CLOSED | 409 | 마감된 Market |
| MARKET_ALREADY_PREDICTED | 409 | 이미 예측 참여함 |
| MARKET_OPTION_NOT_FOUND | 404 | 선택지 없음 |
| MARKET_INVALID_BET_AMOUNT | 400 | 예측 금액 오류 |
| MARKET_PRICE_UPDATE_CONFLICT | 409 | 가격 갱신 동시성 충돌 |
| POINT_INSUFFICIENT | 400 | 포인트 부족 |
| EXTERNAL_SERVICE_TIMEOUT | 504 | 포인트 차감 요청 타임아웃 |
| EXTERNAL_SERVICE_ERROR | 502 | 포인트 서비스 오류 |
```

---

## 16. 완료 기준

- [ ] Market 전용 ErrorCode는 모두 `MARKET_` prefix를 사용한다.
- [ ] 공통 ErrorCode로 표현 가능한 에러를 Market 전용 ErrorCode로 중복 생성하지 않았다.
- [ ] 예측 참여 API는 Prediction을 먼저 저장한 뒤 포인트 차감을 요청한다.
- [ ] 포인트 차감 타임아웃은 `FAILED`가 아니라 `POINT_UNKNOWN`으로 처리한다.
- [ ] 3분 이상 고착된 `POINT_PENDING`도 Scheduler 대사 대상으로 포함한다.
- [ ] 동일 Market에 대해 한 사용자는 하나의 Prediction만 가질 수 있다.
- [ ] `UNIQUE (market_id, member_id)` 제약을 적용한다.
- [ ] 가격 갱신 구간은 DB 비관적 락 또는 Atomic Update로 보호한다.
- [ ] Decimal 필드는 JSON String으로 응답한다.
- [ ] 가격 이력 조회 API는 페이징한다.
- [ ] 정산 시작은 Atomic Update로 처리한다.
- [ ] 정산 일부 실패 시 `SETTLEMENT_IN_PROGRESS` 상태를 유지하고 실패 건만 재시도한다.
- [ ] `SETTLEMENT_IN_PROGRESS`, `SETTLED` 상태는 VOIDED 처리할 수 없다.
- [ ] 환불 실패 또는 타임아웃 시 `REFUND_UNKNOWN` 또는 재시도 대상으로 남긴다.
- [ ] 내부 Scheduler API는 `limit` 기반 chunk 처리를 한다.
