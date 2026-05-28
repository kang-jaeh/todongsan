# API_SPEC.md

> 동네대전 서비스 간 REST 연계 API 명세이다.  
> 각 서비스 내부 API 상세는 `docs/{service}/API_SPEC.md`를 참조한다.

---

## 1. 공통 사항

### 1-1. Base URL

| 서비스 | Internal Base URL |
|---|---|
| Member-Point Service | `http://member-point-service/api/v1` |
| Battle Service | `http://battle-service/api/v1` |
| Market Service | `http://market-service/api/v1` |
| Insight-Reputation Service | `http://insight-reputation-service/api/v1` |

> 외부 요청은 모두 API Gateway를 통해 라우팅된다.

### 1-2. 인증 헤더

API Gateway가 JWT 검증 후 사용자 정보를 헤더로 전달한다.

```
X-Member-Id: {memberId}
X-Member-Role: USER | ADMIN
```

서비스 간 내부 호출에는 위 헤더를 그대로 전달한다.

### 1-3. 응답 포맷

모든 응답은 공통 포맷을 따른다. `CONVENTION.md` 섹션 2 참조.

### 1-4. 멱등성 키

Point 관련 API는 멱등성 키를 필수로 사용한다.

```
Idempotency-Key: {uuid}
```

---

## 2. Member-Point Service 내부 연계 API

다른 서비스가 Member-Point Service를 호출하는 API이다.

---

### 2-1. Point 적립

**Battle 투표 완료, 댓글 작성, Battle 주제 승인 시 호출한다.**

```
POST /api/v1/points/earn
```

**Headers**

```
Idempotency-Key: {uuid}
X-Member-Id: {memberId}
```

**Request Body**

```json
{
  "memberId": 1,
  "type": "EARN_VOTE",
  "amount": 10,
  "referenceId": 42,
  "reason": "Battle 투표 참여 보상"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| memberId | Long | Y | 적립 대상 회원 ID |
| type | String | Y | PointHistoryType (EARN_VOTE, EARN_COMMENT 등) |
| amount | Decimal | Y | 적립 Point |
| referenceId | Long | N | 연관 Battle/Market ID |
| reason | String | N | 적립 사유 |

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "memberId": 1,
    "type": "EARN_VOTE",
    "amount": 10,
    "balanceSnapshot": 60
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| NOT_FOUND | 회원 없음 |
| INTERNAL_ERROR | Point 처리 중 오류 |

---

### 2-2. Point 차감

**Market 예측 참여 시 호출한다.**

```
POST /api/v1/points/spend
```

**Headers**

```
Idempotency-Key: {uuid}
X-Member-Id: {memberId}
```

**Request Body**

```json
{
  "memberId": 1,
  "type": "SPEND_MARKET",
  "amount": 100,
  "referenceId": 7,
  "reason": "Market 예측 참여"
}
```

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "memberId": 1,
    "type": "SPEND_MARKET",
    "amount": 100,
    "balanceSnapshot": 50
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| POINT_INSUFFICIENT | 보유 Point 부족 |
| NOT_FOUND | 회원 없음 |

---

### 2-3. Market 정산 일괄 지급

**Market 정산 완료 시 호출한다. 정답 선택지 참여자 전원에게 일괄 지급한다.**

```
POST /api/v1/points/settlements
```

**Headers**

```
Idempotency-Key: {settlementId}
```

**Request Body**

```json
{
  "marketId": 7,
  "settlementId": "settle-market-7-20260528",
  "items": [
    {
      "memberId": 1,
      "amount": 190.00,
      "reason": "Market 정산 보상"
    },
    {
      "memberId": 2,
      "amount": 95.00,
      "reason": "Market 정산 보상"
    }
  ]
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
    "settledCount": 2,
    "totalSettledAmount": 285.00
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

---

### 2-4. Market 무효 환불

**Market 무효 처리 시 호출한다.**

```
POST /api/v1/points/refunds
```

**Headers**

```
Idempotency-Key: {refundId}
```

**Request Body**

```json
{
  "marketId": 7,
  "refundId": "refund-market-7-20260528",
  "items": [
    {
      "memberId": 1,
      "amount": 100.00,
      "reason": "Market 무효 환불"
    }
  ]
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
    "refundedCount": 1,
    "totalRefundedAmount": 100.00
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

---

## 3. Insight-Reputation Service 내부 연계 API

다른 서비스가 Insight-Reputation Service를 호출하는 API이다.

---

### 3-1. 활동 점수 업데이트

**Battle 투표/댓글 완료 시 호출한다.**

```
POST /api/v1/reputations/activity
```

**Request Body**

```json
{
  "memberId": 1,
  "activityType": "VOTE",
  "referenceId": 42
}
```

| activityType | 설명 |
|---|---|
| VOTE | Battle 투표 |
| COMMENT | Battle 댓글 작성 |
| BATTLE_APPROVED | Battle 주제 등록 승인 |

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "memberId": 1,
    "activityScore": 35
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

---

### 3-2. 예측 정확도 업데이트

**Market 정산 완료 시 호출한다.**

```
POST /api/v1/reputations/prediction
```

**Request Body**

```json
{
  "memberId": 1,
  "marketId": 7,
  "isCorrect": true
}
```

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "memberId": 1,
    "predictionCount": 10,
    "predictionCorrect": 7,
    "predictionAccuracy": 70.00
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

---

## 4. Battle Service 내부 연계 API

Insight-Reputation Service가 AI 분석을 위해 Battle 데이터를 조회하는 API이다.

---

### 4-1. Battle 투표 원본 데이터 조회

```
GET /api/v1/battles/{battleId}/votes/raw
```

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "title": "성수 vs 연남, 데이트하기 어디가 더 좋을까?",
    "optionA": "성수",
    "optionB": "연남",
    "totalVoteCount": 320,
    "optionACount": 195,
    "optionBCount": 125,
    "status": "CLOSED"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

---

## 5. Market Service 내부 연계 API

Insight-Reputation Service가 AI 분석을 위해 Market 데이터를 조회하는 API이다.

---

### 5-1. Market 예측 및 정산 데이터 조회

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
    "totalPool": 400.00,
    "yesPool": 100.00,
    "noPool": 300.00,
    "settledAt": "2026-05-28T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

---

## 6. 서비스 간 호출 흐름 요약

### 6-1. Battle 투표 완료 흐름

```
Client → Gateway → Battle        POST /api/v1/battles/{battleId}/votes
Battle → DB                      투표 저장
Battle → Member-Point            POST /api/v1/points/earn          (10P 지급)
Battle → Insight-Reputation      POST /api/v1/reputations/activity (활동 점수 업데이트)
Battle → Client                  투표 완료 응답
```

### 6-2. Market 예측 참여 흐름

```
Client → Gateway → Market        POST /api/v1/markets/{marketId}/predictions
Market → DB                      PENDING 예측 참여 기록 생성
Market → Member-Point            POST /api/v1/points/spend         (Point 차감)
  성공 시 → Market → DB           예측 참여 CONFIRMED
  실패 시 → Market → DB           예측 참여 FAILED
Market → Client                  결과 응답
```

### 6-3. Market 정산 흐름

```
관리자 → Market                  POST /api/v1/markets/{marketId}/settle
Market → DB                      CONFIRMED 참여 기록 조회 및 정산 금액 계산
Market → Member-Point            POST /api/v1/points/settlements   (정산 보상 일괄 지급)
Market → Insight-Reputation      POST /api/v1/reputations/prediction (예측 정확도 업데이트)
Market → DB                      Market 상태 SETTLED
Market → 관리자                  정산 완료 응답
```

### 6-4. Market 무효 처리 흐름

```
관리자 → Market                  PATCH /api/v1/markets/{marketId}/void
Market → Member-Point            POST /api/v1/points/refunds       (전액 환불)
Market → DB                      Market 상태 VOIDED
Market → 관리자                  무효 처리 완료 응답
```

### 6-5. Insight AI 분석 흐름

```
Insight-Reputation               AI 분석 트리거
  → Battle                       GET /api/v1/battles/{battleId}/votes/raw
  → Market (필요 시)             GET /api/v1/markets/{marketId}/summary
  → Claude API                   프롬프트 + 데이터 전송
  → DB                           분석 결과 저장
  → Client                       AI 리포트 제공
```

---

## 7. 서비스별 상세 API 문서 위치

| 서비스 | 상세 API 명세 |
|---|---|
| Member-Point | `docs/member-point/API_SPEC.md` |
| Battle | `docs/battle/API_SPEC.md` |
| Market | `docs/market/API_SPEC.md` |
| Insight-Reputation | `docs/insight-reputation/API_SPEC.md` |
