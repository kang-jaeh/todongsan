# MARKET_API_SPEC.md

> Market 서비스 API 명세서이다.  
> 본 문서는 `ERROR_POLICY.md`, `MARKET_FAILURE_SCENARIO.md`, `MARKET_ERROR_CODE.md`를 기준으로 작성한다.  
> Market 서비스는 REST 기반 MSA 환경에서 Member-Point 서비스와 직접 연동하므로, 멱등성, 타임아웃, 재시도, 상태 대사, 동시성 제어를 반드시 고려한다.

---

## 1. 공통 API 정책

### 1-1. 공통 응답 포맷

#### 성공 응답

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {},
  "timestamp": "2026-05-29T15:30:00"
}
```

#### 실패 응답

```json
{
  "success": false,
  "errorCode": "MARKET_CLOSED",
  "message": "이미 마감된 Market입니다.",
  "data": null,
  "timestamp": "2026-05-29T15:30:00"
}
```

---

### 1-2. Decimal 응답 정책

Market 서비스는 변동 배당률, 가격 스냅샷, 계약 수량, 풀 금액, 정산 금액 등 정밀도가 중요한 소수점 데이터를 다룬다.

JavaScript 클라이언트는 숫자를 IEEE 754 Double로 처리하므로 소수점 정밀도 손실이 발생할 수 있다.

따라서 Market API에서 소수점 정밀도가 중요한 값은 JSON Number가 아니라 **String**으로 응답한다.

| 필드 | 설명 | 예시 |
|---|---|---|
| `currentPrice` | 현재 선택지 가격 | `"0.18342111"` |
| `priceSnapshot` | 예측 참여 시점 가격 | `"0.18342111"` |
| `contractQuantity` | 구매 계약 수량 | `"12.50000000"` |
| `realPoolAmount` | 실제 참여 포인트 풀 | `"15000.00"` |
| `virtualPoolAmount` | 가격 안정화를 위한 가상 풀 | `"5000.00"` |
| `pointAmount` | 포인트 금액 | `"100.00"` |
| `settlementAmount` | 정산 지급 금액 | `"185.30"` |
| `refundAmount` | 환불 금액 | `"100.00"` |

구현 원칙:

```text
서버 내부 계산: BigDecimal
DB 저장: DECIMAL
API 응답 DTO: String
```

---

### 1-3. Idempotency-Key 정책

포인트 차감, 정산 보상 지급, 환불 요청은 반드시 멱등성 키를 사용한다.

| 작업 | Idempotency-Key |
|---|---|
| 예측 참여 포인트 차감 | `MARKET_PREDICTION_SPEND:market:{marketId}:member:{memberId}` |
| 정산 보상 지급 | `MARKET_SETTLEMENT_REWARD:market:{marketId}:prediction:{predictionId}:member:{memberId}` |
| 무효 처리 환불 | `MARKET_REFUND:market:{marketId}:prediction:{predictionId}:member:{memberId}` |

예측 참여 포인트 차감 키에는 `optionId`를 포함하지 않는다.

이유:

```text
같은 사용자가 YES와 NO를 동시에 요청한 경우,
optionId를 키에 포함하면 서로 다른 요청으로 인식되어 포인트가 중복 차감될 수 있다.
```

---

### 1-4. Client Timeout 처리 가이드

예측 참여 API에서 `502`, `503`, `504` 응답을 받은 경우 프론트엔드는 즉시 실패 화면을 표시하지 않는다.

대신 다음 메시지를 표시한다.

```text
예측 참여 처리 상태를 확인 중입니다. 잠시 후 결과가 반영됩니다.
```

이후 3~5초 간격으로 아래 API를 polling한다.

```http
GET /api/v1/markets/{marketId}/predictions/me
```

| PredictionStatus | Client 처리 |
|---|---|
| `CONFIRMED` | 예측 참여 성공 화면 표시 |
| `FAILED` | 예측 참여 실패 메시지 표시 |
| `POINT_PENDING` | 처리 중 표시 후 계속 polling |
| `POINT_UNKNOWN` | 처리 상태 확인 중 표시 후 계속 polling |
| `SETTLED` | 정산 완료 상태 표시 |
| `REFUNDED` | 환불 완료 상태 표시 |

---

### 1-5. 동시성 제어 원칙

예측 참여 시 다음 작업이 함께 발생한다.

```text
Prediction 생성
→ 포인트 차감
→ 선택지 realPoolAmount 갱신
→ 전체 선택지 가격 재계산
→ 가격 이력 저장
```

여러 사용자가 동시에 참여하면 MarketOption의 pool amount 갱신과 가격 재계산에서 Lost Update 또는 Deadlock이 발생할 수 있다.

따라서 다음 구간은 DB 비관적 락 또는 Atomic Update를 사용하여 순차적으로 처리한다.

- 선택한 MarketOption의 pool amount 갱신
- 전체 선택지 가격 재계산
- price history 저장

MVP에서는 Redis 분산 락보다 DB 비관적 락(`SELECT ... FOR UPDATE`)을 우선 적용한다.

---

### 1-6. 내부 Scheduler Chunk 처리 원칙

대사, 정산 재시도, 환불 재시도 API는 한 번에 전체 대상을 처리하지 않는다.

모든 내부 Scheduler API는 `limit` 쿼리 파라미터를 받는다.

```text
기본값: limit = 100
```

처리 기준:

```text
1. 한 번의 Scheduler 실행에서 최대 limit건만 처리한다.
2. 각 chunk는 짧은 트랜잭션으로 처리한다.
3. 실패한 건은 다음 Scheduler 주기에 다시 처리한다.
4. 장시간 트랜잭션과 DB 커넥션 고갈을 방지한다.
```

---

## 2. Market 목록 조회

```http
GET /api/v1/markets?page=0&size=20&status=ACTIVE
```

### Query Parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `page` | int | X | 페이지 번호. 기본값 0 |
| `size` | int | X | 페이지 크기. 기본값 20 |
| `status` | string | X | Market 상태 필터 |
| `keyword` | string | X | 제목 검색 |

### Response

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "content": [
      {
        "marketId": 1,
        "title": "이번 주 OO구 아파트 가격 변동률은?",
        "status": "ACTIVE",
        "closeAt": "2026-06-01T18:00:00",
        "totalPoolAmount": "25000.00",
        "options": [
          {
            "optionId": 1,
            "content": "0.0% 미만",
            "currentPrice": "0.31250000"
          },
          {
            "optionId": 2,
            "content": "0.0% 이상 ~ 0.3% 미만",
            "currentPrice": "0.68750000"
          }
        ]
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "last": true
  },
  "timestamp": "2026-05-29T15:30:00"
}
```

---

## 3. Market 상세 조회

```http
GET /api/v1/markets/{marketId}
```

### Response

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "marketId": 1,
    "title": "이번 주 OO구 아파트 가격 변동률은?",
    "description": "한국부동산원 데이터를 기준으로 정산합니다.",
    "status": "ACTIVE",
    "closeAt": "2026-06-01T18:00:00",
    "resultAnnounceAt": "2026-06-04T18:00:00",
    "totalPoolAmount": "25000.00",
    "options": [
      {
        "optionId": 1,
        "content": "0.0% 미만",
        "currentPrice": "0.31250000",
        "realPoolAmount": "10000.00",
        "virtualPoolAmount": "5000.00"
      },
      {
        "optionId": 2,
        "content": "0.0% 이상 ~ 0.3% 미만",
        "currentPrice": "0.68750000",
        "realPoolAmount": "15000.00",
        "virtualPoolAmount": "5000.00"
      }
    ]
  },
  "timestamp": "2026-05-29T15:30:00"
}
```

### 발생 가능한 ErrorCode

| ErrorCode | HTTP Status | 설명 |
|---|---:|---|
| `MARKET_NOT_FOUND` | 404 | Market 없음 |

---

## 4. 가격 이력 조회

가격 이력은 예측 참여, 풀 금액 변경, 가격 재계산 시 계속 누적되므로 반드시 페이징한다.

```http
GET /api/v1/markets/{marketId}/price-history?page=0&size=50
```

### Query Parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `page` | int | X | 페이지 번호. 기본값 0 |
| `size` | int | X | 페이지 크기. 기본값 50 |
| `optionId` | long | X | 특정 선택지 이력만 조회 |

### Response

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "content": [
      {
        "historyId": 1,
        "optionId": 1,
        "price": "0.31250000",
        "realPoolAmount": "10000.00",
        "virtualPoolAmount": "5000.00",
        "contractQuantity": "10.00000000",
        "createdAt": "2026-05-29T15:30:00"
      }
    ],
    "page": 0,
    "size": 50,
    "totalElements": 1,
    "totalPages": 1,
    "last": true
  },
  "timestamp": "2026-05-29T15:30:00"
}
```

---

## 5. 예측 참여

```http
POST /api/v1/markets/{marketId}/predictions
```

### Headers

| 이름 | 필수 | 설명 |
|---|---:|---|
| `Authorization` | O | JWT Access Token |
| `Idempotency-Key` | O | 예측 참여 포인트 차감 멱등성 키 |

Idempotency-Key 예시:

```text
MARKET_PREDICTION_SPEND:market:1:member:10
```

### Request

```json
{
  "marketOptionId": 2,
  "pointAmount": "100.00"
}
```

### 처리 흐름

```text
1. Market 조회
2. Market ACTIVE 상태 검증
3. 선택지 검증
4. 중복 참여 검증
5. Prediction POINT_PENDING 저장
6. MarketOption 비관적 락 획득
7. Member-Point 포인트 차감 요청
8. MarketOption realPoolAmount 갱신
9. 전체 선택지 가격 재계산
10. PriceHistory 저장
11. Prediction CONFIRMED 변경
12. 응답 반환
```

### 동시성 제어 주의사항

6번~10번 과정은 MarketOption의 pool amount와 가격을 변경한다.

MVP에서는 다음 방식을 우선 적용한다.

```text
DB 비관적 락 SELECT ... FOR UPDATE
```

동시성 방어 기준:

```text
1. 같은 Market에 대한 가격 갱신은 순차적으로 처리한다.
2. 동일 memberId, marketId의 중복 참여는 UNIQUE 제약으로 차단한다.
3. Idempotency-Key에 optionId를 포함하지 않는다.
```

DB 제약:

```sql
UNIQUE (market_id, member_id)
```

### 성공 Response

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "predictionId": 100,
    "marketId": 1,
    "selectedOptionId": 2,
    "pointAmount": "100.00",
    "priceSnapshot": "0.68750000",
    "contractQuantity": "145.45454545",
    "status": "CONFIRMED"
  },
  "timestamp": "2026-05-29T15:30:00"
}
```

### 처리 불명확 Response

포인트 차감 타임아웃, 5xx, 응답 불명확 상황에서는 `POINT_UNKNOWN` 상태로 남긴다.

```json
{
  "success": true,
  "errorCode": null,
  "message": "예측 참여 처리 상태를 확인 중입니다.",
  "data": {
    "predictionId": 100,
    "marketId": 1,
    "selectedOptionId": 2,
    "pointAmount": "100.00",
    "status": "POINT_UNKNOWN"
  },
  "timestamp": "2026-05-29T15:30:00"
}
```

### Client 처리 가이드

예측 참여 API에서 `502`, `503`, `504` 응답을 받거나 응답 본문의 상태가 `POINT_UNKNOWN`인 경우 프론트엔드는 실패 화면을 표시하지 않는다.

대신 다음 API를 3~5초 간격으로 polling한다.

```http
GET /api/v1/markets/{marketId}/predictions/me
```

### 발생 가능한 ErrorCode

| ErrorCode | HTTP Status | 설명 |
|---|---:|---|
| `MARKET_NOT_FOUND` | 404 | Market 없음 |
| `MARKET_NOT_ACTIVE` | 409 | 참여 가능한 상태가 아님 |
| `MARKET_CLOSED` | 409 | 마감된 Market |
| `MARKET_ALREADY_PREDICTED` | 409 | 이미 예측 참여함 |
| `MARKET_OPTION_NOT_FOUND` | 404 | 선택지 없음 |
| `MARKET_INVALID_BET_AMOUNT` | 400 | 예측 금액 오류 |
| `POINT_INSUFFICIENT` | 400 | 포인트 부족 |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | 멱등성 키 누락 |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | 멱등성 키 충돌 |
| `EXTERNAL_SERVICE_TIMEOUT` | 504 | 포인트 차감 요청 타임아웃 |
| `EXTERNAL_SERVICE_UNAVAILABLE` | 503 | 포인트 서비스 연결 실패 |
| `EXTERNAL_SERVICE_ERROR` | 502 | 포인트 서비스 5xx |
| `MARKET_PRICE_UPDATE_CONFLICT` | 409 | 가격 갱신 동시성 충돌 |

---

## 6. 내 예측 상태 조회

```http
GET /api/v1/markets/{marketId}/predictions/me
```

`POINT_UNKNOWN`, `POINT_PENDING` 상태를 클라이언트가 확인하기 위한 API이다.

### Response

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "predictionId": 100,
    "marketId": 1,
    "selectedOptionId": 2,
    "pointAmount": "100.00",
    "priceSnapshot": "0.68750000",
    "contractQuantity": "145.45454545",
    "status": "POINT_UNKNOWN",
    "createdAt": "2026-05-29T15:30:00",
    "updatedAt": "2026-05-29T15:31:00"
  },
  "timestamp": "2026-05-29T15:31:00"
}
```

### 발생 가능한 ErrorCode

| ErrorCode | HTTP Status | 설명 |
|---|---:|---|
| `MARKET_NOT_FOUND` | 404 | Market 없음 |
| `MARKET_PREDICTION_NOT_FOUND` | 404 | 내 예측 없음 |

---

## 7. 관리자 Market 생성

```http
POST /api/v1/admin/markets
```

### Request

```json
{
  "title": "이번 주 OO구 아파트 가격 변동률은?",
  "description": "한국부동산원 데이터를 기준으로 정산합니다.",
  "closeAt": "2026-06-01T18:00:00",
  "resultAnnounceAt": "2026-06-04T18:00:00",
  "options": [
    {
      "content": "0.0% 미만",
      "lowerBound": null,
      "upperBound": "0.0000",
      "lowerInclusive": false,
      "upperInclusive": false
    },
    {
      "content": "0.0% 이상 ~ 0.3% 미만",
      "lowerBound": "0.0000",
      "upperBound": "0.3000",
      "lowerInclusive": true,
      "upperInclusive": false
    }
  ]
}
```

### 검증 규칙

```text
1. 선택지는 최소 2개 이상이어야 한다.
2. 선택지 범위가 서로 겹치면 안 된다.
3. 선택지 범위 사이에 빈 구간이 있으면 안 된다.
4. 경계값 포함 여부가 명확해야 한다.
5. 실제 정산 값은 정확히 하나의 선택지에만 매칭되어야 한다.
```

### 발생 가능한 ErrorCode

| ErrorCode | HTTP Status | 설명 |
|---|---:|---|
| `FORBIDDEN` | 403 | 관리자 권한 없음 |
| `VALIDATION_FAILED` | 400 | 요청 값 검증 실패 |
| `MARKET_INVALID_OPTION` | 400 | 선택지 구성 오류 |
| `MARKET_INVALID_OPTION_RANGE` | 400 | 선택지 범위 오류 |

---

## 8. 관리자 결과 확정

```http
PATCH /api/v1/admin/markets/{marketId}/result
```

관리자가 공공 데이터 또는 수동 확인 결과를 바탕으로 Market 결과를 확정한다.

이 API는 결과를 확정할 뿐, 실제 포인트 정산을 실행하지 않는다.  
정산 실행은 별도의 정산 API 또는 Scheduler가 담당한다.

### Request

```json
{
  "winningOptionId": 2,
  "actualValue": "0.1834",
  "source": "KOREA_REAL_ESTATE_BOARD"
}
```

### 발생 가능한 ErrorCode

| ErrorCode | HTTP Status | 설명 |
|---|---:|---|
| `MARKET_NOT_FOUND` | 404 | Market 없음 |
| `MARKET_INVALID_STATUS` | 409 | 결과 확정 가능한 상태가 아님 |
| `MARKET_WINNING_OPTION_NOT_FOUND` | 409 | 정답 선택지 계산 실패 |
| `MARKET_INVALID_SETTLEMENT_DATA` | 409 | 정산 데이터 비정상 |
| `FORBIDDEN` | 403 | 관리자 권한 없음 |

---

## 9. 관리자 정산 실행

```http
POST /api/v1/admin/markets/{marketId}/settle
```

### 처리 흐름

```text
1. Market 조회
2. Market 상태 검증
3. Atomic Update로 SETTLEMENT_IN_PROGRESS 획득
4. 정산 대상 Prediction 조회
5. 정산 금액 계산
6. Member-Point 정산 보상 지급 요청
7. 성공 건 Prediction SETTLED
8. 실패 건 재시도 대상으로 기록
9. 모든 지급 성공 시 Market SETTLED
10. 일부 실패 시 SETTLEMENT_IN_PROGRESS 유지
```

### Atomic Update

```sql
UPDATE market
SET status = 'SETTLEMENT_IN_PROGRESS'
WHERE id = :marketId
AND status IN ('CLOSED', 'DATA_PENDING');
```

`affected row = 0`이면 이미 정산 중이거나 정산 가능한 상태가 아니므로 중단한다.

### 발생 가능한 ErrorCode

| ErrorCode | HTTP Status | 설명 |
|---|---:|---|
| `MARKET_NOT_FOUND` | 404 | Market 없음 |
| `MARKET_INVALID_STATUS` | 409 | 정산 가능한 상태가 아님 |
| `MARKET_ALREADY_SETTLED` | 409 | 이미 정산 완료 |
| `MARKET_NO_PREDICTIONS` | 409 | 정산 대상 없음 |
| `MARKET_INVALID_SETTLEMENT` | 409 | 정산 조건 미충족 |
| `MARKET_SETTLEMENT_FAILED` | 500 | 정산 실패 |
| `EXTERNAL_SERVICE_TIMEOUT` | 504 | 포인트 지급 요청 타임아웃 |
| `EXTERNAL_SERVICE_ERROR` | 502 | 포인트 서비스 오류 |

---

## 10. 관리자 Market 무효 처리

```http
PATCH /api/v1/admin/markets/{marketId}/void
```

### VOIDED 가능 상태

```text
PENDING
ACTIVE
CLOSED
DATA_PENDING
```

### VOIDED 불가능 상태

```text
SETTLEMENT_IN_PROGRESS
SETTLED
```

정산이 이미 시작된 Market은 관리자도 VOIDED 처리할 수 없다.  
정산 시작 이후 문제가 발생하면 관리자 수동 보정 대상으로 남긴다.

### 발생 가능한 ErrorCode

| ErrorCode | HTTP Status | 설명 |
|---|---:|---|
| `MARKET_NOT_FOUND` | 404 | Market 없음 |
| `MARKET_CANNOT_VOID` | 409 | 무효 처리 불가능한 상태 |
| `MARKET_REFUND_FAILED` | 500 | 환불 실패 |
| `EXTERNAL_SERVICE_TIMEOUT` | 504 | 환불 요청 타임아웃 |
| `EXTERNAL_SERVICE_ERROR` | 502 | 포인트 서비스 오류 |
| `FORBIDDEN` | 403 | 관리자 권한 없음 |

---

## 11. 내부 Scheduler API

> 내부 Scheduler API는 외부 클라이언트에 공개하지 않는다.  
> Gateway 또는 내부 네트워크에서만 접근 가능하도록 제한한다.

### 11-1. 포인트 차감 상태 대사

```http
POST /internal/api/v1/markets/predictions/reconcile-point?limit=100
```

대상:

```text
1. PredictionStatus = POINT_UNKNOWN
2. updatedAt 기준 3분 이상 지난 POINT_PENDING
```

처리:

```text
Idempotency-Key로 Member-Point 처리 이력 조회
→ 성공 확인 시 CONFIRMED
→ 실패 확인 시 FAILED
→ 처리 이력 없음 시 재시도 또는 다음 주기로 보류
```

---

### 11-2. 정산 실패 건 재시도

```http
POST /internal/api/v1/markets/settlements/retry-failed?limit=100
```

대상:

```text
MarketStatus = SETTLEMENT_IN_PROGRESS
정산 지급 실패로 기록된 Prediction
```

---

### 11-3. 환불 실패 건 재시도

```http
POST /internal/api/v1/markets/refunds/retry-failed?limit=100
```

대상:

```text
PredictionStatus = REFUND_UNKNOWN
또는 환불 실패로 기록된 Prediction
```

---

### 11-4. 공공 데이터 수집 재시도

```http
POST /internal/api/v1/markets/settlement-data/retry-fetch?limit=100
```

대상:

```text
MarketStatus = DATA_PENDING
예상 수집일로부터 3일 이내
```

---

## 12. Market API 완료 기준

- [ ] Decimal 필드는 JSON String으로 응답한다.
- [ ] 가격 이력 조회 API는 page/size 페이징을 지원한다.
- [ ] 예측 참여 API는 `Idempotency-Key`를 필수로 받는다.
- [ ] 예측 참여 API는 Prediction을 먼저 `POINT_PENDING`으로 저장한다.
- [ ] 포인트 차감 타임아웃 시 `POINT_UNKNOWN`으로 처리한다.
- [ ] 클라이언트는 502/503/504 또는 `POINT_UNKNOWN` 수신 시 `predictions/me`를 polling한다.
- [ ] MarketOption 가격 갱신 구간은 DB 비관적 락 또는 Atomic Update로 보호한다.
- [ ] 한 사용자는 하나의 Market에 하나의 Prediction만 가질 수 있다.
- [ ] 정산 시작은 Atomic Update로 `SETTLEMENT_IN_PROGRESS`를 획득한다.
- [ ] 내부 Scheduler API는 `limit` 파라미터로 chunk 처리한다.
- [ ] `SETTLEMENT_IN_PROGRESS`, `SETTLED` 상태는 VOIDED 처리할 수 없다.
