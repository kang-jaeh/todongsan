# API_SPEC.md - Market Service

> Market Service의 모든 API 명세이다.  
> 부동산 관련 예측 Market 생성, 참여, 정산을 담당한다.

---

## 1. 공통 사항

### 1-1. Base URL

```
/api/v1
```

### 1-2. 인증

- 모든 API는 JWT 인증이 필요하다.
- 관리자 전용 API는 `X-Member-Role: ADMIN` 헤더가 필요하다.

### 1-3. Market 상태

```java
public enum MarketStatus {
    PENDING,        // 검수 대기
    ACTIVE,         // 예측 참여 가능
    CLOSED,         // 예측 마감 (정산 대기)
    DATA_PENDING,   // 공공 데이터 수신 대기
    SETTLED,        // 정산 완료
    VOIDED          // 무효 처리
}
```

### 1-4. 예측 참여 상태

```java
public enum PredictionStatus {
    PENDING,    // Point 차감 대기
    CONFIRMED,  // 참여 확정 (Point 차감 완료)
    FAILED,     // 참여 실패 (Point 차감 실패)
    SETTLED,    // 정산 완료
    REFUNDED    // 환불 완료 (Market 무효 시)
}
```

---

## 2. Market 관리

### 2-1. Market 등록

```
POST /api/v1/markets
```

**Request Body**

```json
{
  "title": "다음 주 서울 아파트 매매가격지수는 상승할까?",
  "description": "한국부동산원 주간 아파트 가격동향 발표 기준",
  "optionA": "YES",
  "optionB": "NO",
  "judgmentCriteria": "발표된 서울 아파트 매매가격지수 변동률이 0보다 크면 YES",
  "dataSource": "한국부동산원 주간 아파트 가격동향",
  "deadline": "2026-06-01T23:59:00",
  "settlementDate": "2026-06-03T18:00:00"
}
```

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "marketId": 7,
    "title": "다음 주 서울 아파트 매매가격지수는 상승할까?",
    "status": "PENDING",
    "createdAt": "2026-05-28T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

- `UNAUTHORIZED`: 로그인 필요
- `INTERNAL_ERROR`: 서버 오류

---

### 2-2. Market 목록 조회

```
GET /api/v1/markets?status={status}&page={page}&size={size}
```

**Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| status | String | N | MarketStatus 필터 |
| page | Integer | N | 페이지 번호 (기본값: 0) |
| size | Integer | N | 페이지 크기 (기본값: 20) |

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "content": [
      {
        "marketId": 7,
        "title": "다음 주 서울 아파트 매매가격지수는 상승할까?",
        "status": "ACTIVE",
        "optionA": "YES",
        "optionB": "NO",
        "totalPool": 1500.00,
        "optionAPool": 900.00,
        "optionBPool": 600.00,
        "deadline": "2026-06-01T23:59:00",
        "createdAt": "2026-05-28T10:00:00"
      }
    ],
    "totalElements": 50,
    "totalPages": 3,
    "size": 20,
    "number": 0
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

---

### 2-3. Market 상세 조회

```
GET /api/v1/markets/{marketId}
```

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "marketId": 7,
    "title": "다음 주 서울 아파트 매매가격지수는 상승할까?",
    "description": "한국부동산원 주간 아파트 가격동향 발표 기준",
    "status": "ACTIVE",
    "optionA": "YES",
    "optionB": "NO",
    "totalPool": 1500.00,
    "optionAPool": 900.00,
    "optionBPool": 600.00,
    "judgmentCriteria": "발표된 서울 아파트 매매가격지수 변동률이 0보다 크면 YES",
    "dataSource": "한국부동산원 주간 아파트 가격동향",
    "deadline": "2026-06-01T23:59:00",
    "settlementDate": "2026-06-03T18:00:00",
    "resultOption": null,
    "settledAt": null,
    "createdAt": "2026-05-28T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

- `NOT_FOUND`: Market 없음

---

### 2-4. Market 승인 (관리자)

```
PATCH /api/v1/markets/{marketId}/approve
```

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "marketId": 7,
    "status": "ACTIVE",
    "approvedAt": "2026-05-28T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

- `FORBIDDEN`: 관리자 권한 필요
- `NOT_FOUND`: Market 없음
- `BAD_REQUEST`: 이미 승인되었거나 승인 불가 상태

---

### 2-5. Market 거부 (관리자)

```
PATCH /api/v1/markets/{marketId}/reject
```

**Request Body**

```json
{
  "reason": "판정 기준이 모호합니다."
}
```

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "marketId": 7,
    "status": "REJECTED",
    "rejectedAt": "2026-05-28T10:00:00",
    "rejectReason": "판정 기준이 모호합니다."
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

- `FORBIDDEN`: 관리자 권한 필요
- `NOT_FOUND`: Market 없음

---

## 3. 예측 참여

### 3-1. 예측 참여

```
POST /api/v1/markets/{marketId}/predictions
```

**Headers**

```
Idempotency-Key: {uuid}
```

**Request Body**

```json
{
  "option": "YES",
  "amount": 100
}
```

**Response - 성공**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "predictionId": 12,
    "marketId": 7,
    "option": "YES",
    "amount": 100,
    "status": "CONFIRMED",
    "createdAt": "2026-05-28T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Response - 실패**

```json
{
  "success": false,
  "errorCode": "POINT_INSUFFICIENT",
  "message": "포인트가 부족합니다. 현재 보유: 50P, 필요: 100P",
  "data": null,
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

- `POINT_INSUFFICIENT`: 보유 Point 부족
- `MARKET_CLOSED`: 마감된 Market
- `ALREADY_PREDICTED`: 이미 참여한 Market
- `NOT_FOUND`: Market 없음

---

### 3-2. 예상 정산 비율 조회

```
GET /api/v1/markets/{marketId}/predictions/ratio
```

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "marketId": 7,
    "totalPool": 1500.00,
    "systemFeeRate": 5.0,
    "settlementPool": 1425.00,
    "options": [
      {
        "option": "YES",
        "pool": 900.00,
        "expectedRatio": 1.58,
        "participantCount": 18
      },
      {
        "option": "NO",
        "pool": 600.00,
        "expectedRatio": 2.38,
        "participantCount": 12
      }
    ]
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

- `NOT_FOUND`: Market 없음

---

### 3-3. 내 예측 참여 조회

```
GET /api/v1/markets/{marketId}/predictions/mine
```

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "predictionId": 12,
    "marketId": 7,
    "option": "YES",
    "amount": 100,
    "status": "CONFIRMED",
    "settlementAmount": null,
    "createdAt": "2026-05-28T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

- `NOT_FOUND`: Market 또는 예측 참여 없음

---

## 4. 정산 및 무효 (관리자)

### 4-1. Market 정산

```
POST /api/v1/markets/{marketId}/settle
```

**Request Body**

```json
{
  "resultOption": "YES",
  "settlementNote": "한국부동산원 발표: 서울 아파트 매매가격지수 0.02% 상승"
}
```

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "marketId": 7,
    "status": "SETTLED",
    "resultOption": "YES",
    "totalPool": 1500.00,
    "systemFee": 75.00,
    "settlementPool": 1425.00,
    "winnerPool": 900.00,
    "finalRatio": 1.58,
    "settledCount": 18,
    "totalSettledAmount": 1425.00,
    "settledAt": "2026-05-28T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

- `FORBIDDEN`: 관리자 권한 필요
- `NOT_FOUND`: Market 없음
- `INVALID_SETTLE`: 정산 조건 미충족

---

### 4-2. Market 무효 처리

```
PATCH /api/v1/markets/{marketId}/void
```

**Request Body**

```json
{
  "voidReason": "공공 데이터 7일 초과 미공개"
}
```

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "marketId": 7,
    "status": "VOIDED",
    "voidReason": "공공 데이터 7일 초과 미공개",
    "refundedCount": 30,
    "totalRefundedAmount": 1500.00,
    "voidedAt": "2026-05-28T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

- `FORBIDDEN`: 관리자 권한 필요
- `NOT_FOUND`: Market 없음

---

## 5. 내부 연계 API

### 5-1. Market 요약 데이터 조회 (Insight 전용)

```
GET /api/v1/markets/{marketId}/summary
```

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "marketId": 7,
    "title": "다음 주 서울 아파트 매매가격지수는 상승할까?",
    "status": "SETTLED",
    "resultOption": "YES",
    "totalPool": 1500.00,
    "yesPool": 900.00,
    "noPool": 600.00,
    "settledAt": "2026-05-28T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

- `NOT_FOUND`: Market 없음
