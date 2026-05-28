
Claude configuration file at /Users/kimmo/.claude.json is corrupted: Unexpected end of JSON input

Claude configuration file at /Users/kimmo/.claude.json is corrupted
The corrupted file has been backed up to: /Users/kimmo/.claude.json.corrupted.1779973278589
A backup file exists at: /Users/kimmo/.claude.json.backup
You can manually restore it by running: cp "/Users/kimmo/.claude.json.backup" "/Users/kimmo/.claude.json"

# API_SPEC.md - Insight-Reputation Service

> Insight-Reputation Service의 상세 API 명세이다.  
> 사용자 신뢰도(Reputation) 조회 및 관리, AI 분석 리포트 생성 기능을 제공한다.

---

## 1. 공통 사항

### 1-1. Base URL

```
https://api.todongsan.com/api/v1 (외부)
http://insight-reputation-service/api/v1 (내부)
```

### 1-2. 인증

모든 API는 JWT 인증이 필요하다. API Gateway가 검증 후 헤더로 전달한다.

```
X-Member-Id: {memberId}
X-Member-Role: USER | ADMIN
```

### 1-3. 응답 포맷

[CONVENTION.md 섹션 2](../CONVENTION.md#2-공통-응답-포맷) 참조

---

## 2. 서비스 간 내부 연계 API

다른 서비스가 Insight-Reputation Service를 호출하는 API이다.

[루트 API_SPEC.md 섹션 3](../API_SPEC.md#3-insight-reputation-service-내부-연계-api) 참조

---

## 3. Reputation 조회

### 3-1. 내 신뢰도 조회

```
GET /api/v1/reputations/me
```

**인증 필요:** O  
**Point 소비:** X

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "memberId": 1,
    "activityScore": 35,
    "predictionCount": 10,
    "predictionCorrect": 7,
    "predictionAccuracy": 70.00,
    "residenceRegion": "성동구",
    "residenceVerified": false,
    "activityConfirmed": true,
    "visitCertifications": [
      {
        "regionId": 1,
        "regionName": "성수동",
        "certifiedAt": "2026-05-20T14:30:00",
        "method": "GPS"
      },
      {
        "regionId": 2,
        "regionName": "연남동",
        "certifiedAt": "2026-05-15T16:45:00", 
        "method": "COMMENT"
      }
    ]
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

---

### 3-2. 특정 회원 신뢰도 조회

```
GET /api/v1/reputations/{memberId}
```

**인증 필요:** O  
**Point 소비:** X

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "memberId": 2,
    "activityScore": 28,
    "predictionAccuracy": 65.00,
    "residenceRegion": "마포구",
    "residenceVerified": false,
    "activityConfirmed": true,
    "visitCertificationCount": 3
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| NOT_FOUND | 존재하지 않는 회원 |

---

## 4. 방문 인증

### 4-1. 방문 인증 등록

```
POST /api/v1/reputations/visit-certifications
```

**인증 필요:** O  
**Point 소비:** X

**Request Body**

```json
{
  "regionId": 1,
  "method": "GPS",
  "data": {
    "latitude": 37.544876,
    "longitude": 127.055678
  }
}
```

**또는**

```json
{
  "regionId": 1,
  "method": "COMMENT",
  "data": {
    "commentId": 42
  }
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| regionId | Long | Y | 인증할 지역 ID |
| method | String | Y | GPS 또는 COMMENT |
| data | Object | Y | 인증 방법별 데이터 |

**GPS 방식 data**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| latitude | Double | Y | 위도 |
| longitude | Double | Y | 경도 |

**COMMENT 방식 data**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| commentId | Long | Y | 해당 지역 Battle 댓글 ID |

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "regionId": 1,
    "regionName": "성수동",
    "method": "GPS",
    "certifiedAt": "2026-05-28T10:00:00",
    "nextAvailableDate": "2026-06-27T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| NOT_FOUND | 존재하지 않는 지역 또는 댓글 |
| FORBIDDEN | GPS 사용 불가 환경 (HTTP 환경) |
| TOO_FAR_FROM_REGION | GPS 좌표가 지역 중심에서 3km 초과 |
| COMMENT_REGION_MISMATCH | 댓글이 해당 지역 Battle이 아님 |
| ALREADY_CERTIFIED_RECENTLY | 동일 지역 30일 내 재인증 시도 |

**ALREADY_CERTIFIED_RECENTLY 응답 예시**

```json
{
  "success": false,
  "errorCode": "ALREADY_CERTIFIED_RECENTLY",
  "message": "성수동은 2026-06-20일부터 재인증 가능합니다.",
  "data": {
    "nextAvailableDate": "2026-06-20T00:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

---

### 4-2. 내 방문 인증 내역 조회

```
GET /api/v1/reputations/visit-certifications/mine
```

**인증 필요:** O  
**Point 소비:** X

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": [
    {
      "regionId": 1,
      "regionName": "성수동",
      "method": "GPS",
      "certifiedAt": "2026-05-20T14:30:00",
      "nextAvailableDate": "2026-06-19T14:30:00"
    },
    {
      "regionId": 2,
      "regionName": "연남동", 
      "method": "COMMENT",
      "certifiedAt": "2026-05-15T16:45:00",
      "nextAvailableDate": "2026-06-14T16:45:00"
    }
  ],
  "timestamp": "2026-05-28T10:00:00"
}
```

---

## 5. AI 분석 리포트

### 5-1. Battle AI 분석 리포트 생성

```
POST /api/v1/insights/battles/{battleId}/report
```

**인증 필요:** O  
**Point 소비:** 80P (즉시 차감)

**Headers**

```
Idempotency-Key: {uuid}
```

**Response - 새로 생성**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "reportId": "battle-42-report-20260528",
    "title": "성수 vs 연남, 데이트하기 어디가 더 좋을까?",
    "summary": "전체 투표에서는 성수가 61%로 우세했습니다. 다만 방문 인증 사용자 그룹에서는 연남 선호가 더 높게 나타났습니다. 댓글에서는 성수의 상권과 접근성이 장점으로 언급되었고, 연남은 분위기와 산책 코스가 강점으로 언급되었습니다.",
    "analysisData": {
      "totalVotes": 320,
      "optionAVotes": 195,
      "optionBVotes": 125,
      "visitCertifiedVotes": {
        "optionA": 15,
        "optionB": 25
      },
      "keyComments": [
        "성수는 교통이 편해서 만나기 좋음",
        "연남은 골목 구경하는 재미가 있어요"
      ]
    },
    "generatedAt": "2026-05-28T10:00:00",
    "pointCharged": 80
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Response - 기존 리포트 존재**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "reportId": "battle-42-report-20260525",
    "summary": "...",
    "analysisData": { ... },
    "generatedAt": "2026-05-25T15:30:00",
    "pointCharged": 0
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| NOT_FOUND | 존재하지 않는 Battle |
| BATTLE_NOT_CLOSED | Battle이 아직 종료되지 않음 |
| POINT_INSUFFICIENT | Point 부족 (80P 미만) |
| AI_GENERATION_FAILED | Claude API 호출 실패 (Point 환불 처리됨) |

---

### 5-2. Battle AI 분석 리포트 조회

```
GET /api/v1/insights/battles/{battleId}/report
```

**인증 필요:** O  
**Point 소비:** X

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "reportId": "battle-42-report-20260525",
    "title": "성수 vs 연남, 데이트하기 어디가 더 좋을까?",
    "summary": "전체 투표에서는 성수가 61%로 우세했습니다...",
    "analysisData": { ... },
    "generatedAt": "2026-05-25T15:30:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| NOT_FOUND | Battle 또는 리포트가 존재하지 않음 |

---

### 5-3. Market AI 정보 요약 생성

```
POST /api/v1/insights/markets/{marketId}/report
```

**인증 필요:** O  
**Point 소비:** 80P (즉시 차감)

**Headers**

```
Idempotency-Key: {uuid}
```

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "marketId": 7,
    "reportId": "market-7-report-20260528",
    "title": "다음 주 서울 아파트 매매가격지수는 상승할까?",
    "summary": "YES 근거: 최근 3주간 서울 아파트 매매가격지수는 상승세를 보였습니다. NO 근거: 거래량은 여전히 낮은 수준이며, 일부 지역은 보합세를 유지하고 있습니다. 주의: AI는 예측 방향을 추천하지 않으며, 사용자의 판단을 돕기 위한 정보만 제공합니다.",
    "analysisData": {
      "yesReasons": [
        "최근 3주간 매매가격지수 상승세",
        "신규 공급 물량 감소"
      ],
      "noReasons": [
        "거래량 저조",
        "일부 지역 보합세"
      ],
      "dataSource": "국토교통부 실거래가 공개시스템",
      "judgmentCriteria": "매매가격지수 전주 대비 0.1% 이상 상승"
    },
    "generatedAt": "2026-05-28T10:00:00",
    "pointCharged": 80
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| NOT_FOUND | 존재하지 않는 Market |
| POINT_INSUFFICIENT | Point 부족 (80P 미만) |
| AI_GENERATION_FAILED | Claude API 호출 실패 (Point 환불 처리됨) |

---

### 5-4. Market AI 정보 요약 조회

```
GET /api/v1/insights/markets/{marketId}/report
```

**인증 필요:** O  
**Point 소비:** X

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "marketId": 7,
    "reportId": "market-7-report-20260525",
    "title": "다음 주 서울 아파트 매매가격지수는 상승할까?",
    "summary": "YES 근거: 최근 3주간 서울 아파트 매매가격지수는 상승세를 보였습니다...",
    "analysisData": { ... },
    "generatedAt": "2026-05-25T15:30:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| NOT_FOUND | Market 또는 리포트가 존재하지 않음 |
