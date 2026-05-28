# API_SPEC.md - Battle Service

> Battle Service의 클라이언트 대상 API 명세이다.  
> 서비스 간 내부 연계 API는 루트 `API_SPEC.md`를 참조한다.

---

## 1. 공통 사항

### 1-1. Base URL

```
https://todongsan.com/api/v1
```

### 1-2. 인증

JWT Bearer Token을 사용한다.

```
Authorization: Bearer {token}
```

### 1-3. 응답 포맷

모든 응답은 공통 `ApiResponse<T>` 포맷을 따른다. `CONVENTION.md` 섹션 2 참조.

---

## 2. Battle 관리

### 2-1. Battle 주제 등록

```
POST /api/v1/battles
```

**인증**: 필요

**Request Body**

```json
{
  "title": "성수 vs 연남, 데이트하기 어디가 더 좋을까?",
  "optionA": "성수",
  "optionB": "연남",
  "description": "주말 데이트 코스로 어디가 더 매력적인지 투표해주세요!"
}
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
    "status": "PENDING",
    "createdAt": "2026-05-28T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| UNAUTHORIZED | JWT 토큰 없음 또는 만료 |
| POINT_INSUFFICIENT | Battle 생성권 부족 |

---

### 2-2. Battle 목록 조회

```
GET /api/v1/battles?status={status}&page={page}&size={size}
```

**인증**: 불필요

**Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| status | String | N | ACTIVE, CLOSED (기본: ACTIVE) |
| page | Integer | N | 페이지 번호 (기본: 0) |
| size | Integer | N | 페이지 크기 (기본: 20) |

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "content": [
      {
        "battleId": 42,
        "title": "성수 vs 연남, 데이트하기 어디가 더 좋을까?",
        "optionA": "성수",
        "optionB": "연남",
        "status": "ACTIVE",
        "totalVoteCount": 125,
        "hasVoted": false,
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

### 2-3. Battle 상세 조회

```
GET /api/v1/battles/{battleId}
```

**인증**: 불필요

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
    "description": "주말 데이트 코스로 어디가 더 매력적인지 투표해주세요!",
    "status": "ACTIVE",
    "totalVoteCount": 125,
    "hasVoted": false,
    "myVoteOption": null,
    "createdAt": "2026-05-28T10:00:00",
    "closedAt": null
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| NOT_FOUND | Battle 없음 |

---

### 2-4. Battle 승인 (관리자)

```
PATCH /api/v1/battles/{battleId}/approve
```

**인증**: 필요 (관리자)

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "status": "ACTIVE"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| FORBIDDEN | 관리자 권한 없음 |
| NOT_FOUND | Battle 없음 |
| BATTLE_CLOSED | 이미 처리된 Battle |

---

### 2-5. Battle 거절 (관리자)

```
PATCH /api/v1/battles/{battleId}/reject
```

**인증**: 필요 (관리자)

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "status": "CANCELLED"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| FORBIDDEN | 관리자 권한 없음 |
| NOT_FOUND | Battle 없음 |
| BATTLE_CLOSED | 이미 처리된 Battle |

---

### 2-6. Battle 강제 취소 (관리자)

```
PATCH /api/v1/battles/{battleId}/cancel
```

**인증**: 필요 (관리자)

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "status": "CANCELLED"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| FORBIDDEN | 관리자 권한 없음 |
| NOT_FOUND | Battle 없음 |

---

## 3. 투표

### 3-1. 투표 참여

```
POST /api/v1/battles/{battleId}/votes
```

**인증**: 필요

**Request Body**

```json
{
  "option": "A"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| option | String | Y | "A" 또는 "B" |

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "myVoteOption": "A",
    "result": {
      "totalVoteCount": 126,
      "optionACount": 76,
      "optionBCount": 50,
      "optionAPercentage": 60.32,
      "optionBPercentage": 39.68
    }
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| UNAUTHORIZED | JWT 토큰 없음 또는 만료 |
| ALREADY_VOTED | 이미 투표 완료 |
| BATTLE_CLOSED | 종료된 Battle |
| NOT_FOUND | Battle 없음 |

---

### 3-2. 투표 결과 조회

```
GET /api/v1/battles/{battleId}/result
```

**인증**: 선택

**Response (진행 중 + 미투표/비회원)**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "status": "ACTIVE",
    "totalVoteCount": 126,
    "hasVoted": false,
    "result": null
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Response (진행 중 + 투표 완료)**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "status": "ACTIVE",
    "totalVoteCount": 126,
    "hasVoted": true,
    "myVoteOption": "A",
    "result": {
      "optionACount": 76,
      "optionBCount": 50,
      "optionAPercentage": 60.32,
      "optionBPercentage": 39.68
    }
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Response (종료 직후 + 투표 완료)**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "status": "CLOSED",
    "totalVoteCount": 320,
    "hasVoted": true,
    "myVoteOption": "A",
    "result": {
      "optionACount": 195,
      "optionBCount": 125,
      "optionAPercentage": 60.94,
      "optionBPercentage": 39.06,
      "crossAnalysis": {
        "by_age": {
          "20대": { "optionAPercentage": 65.2, "optionBPercentage": 34.8 },
          "30대": { "optionAPercentage": 58.1, "optionBPercentage": 41.9 }
        },
        "by_gender": {
          "남성": { "optionAPercentage": 62.5, "optionBPercentage": 37.5 },
          "여성": { "optionAPercentage": 59.3, "optionBPercentage": 40.7 }
        }
      }
    }
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Response (종료 후 72시간 경과)**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "status": "CLOSED",
    "totalVoteCount": 320,
    "hasVoted": false,
    "result": {
      "optionAPercentage": 60.94,
      "optionBPercentage": 39.06
    }
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Response (종료 직후 + 미투표/비회원)**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "status": "CLOSED",
    "totalVoteCount": 320,
    "hasVoted": false,
    "result": null
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| NOT_FOUND | Battle 없음 |

---

### 3-3. 상세 교차분석 조회 (30P 소비)

```
GET /api/v1/battles/{battleId}/result/cross
```

**인증**: 필요

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "crossAnalysis": {
      "by_residence": {
        "강남구": { "optionAPercentage": 45.2, "optionBPercentage": 54.8, "count": 31 },
        "마포구": { "optionAPercentage": 71.4, "optionBPercentage": 28.6, "count": 42 },
        "성동구": { "optionAPercentage": 83.3, "optionBPercentage": 16.7, "count": 18 }
      },
      "by_age_residence": {
        "20대_강남구": { "optionAPercentage": 50.0, "optionBPercentage": 50.0, "count": 16 },
        "30대_강남구": { "optionAPercentage": 40.0, "optionBPercentage": 60.0, "count": 15 }
      }
    }
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| UNAUTHORIZED | JWT 토큰 없음 또는 만료 |
| POINT_INSUFFICIENT | Point 부족 |
| NOT_FOUND | Battle 없음 |
| BATTLE_CLOSED | 진행 중인 Battle (결과 비공개) |

---

### 3-4. 방문 인증자 필터 결과 (30P 소비)

```
GET /api/v1/battles/{battleId}/result/certified
```

**인증**: 필요

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "battleId": 42,
    "certifiedOnly": true,
    "totalCertifiedCount": 89,
    "result": {
      "optionACount": 58,
      "optionBCount": 31,
      "optionAPercentage": 65.17,
      "optionBPercentage": 34.83
    }
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| UNAUTHORIZED | JWT 토큰 없음 또는 만료 |
| POINT_INSUFFICIENT | Point 부족 |
| NOT_FOUND | Battle 없음 |
| BATTLE_CLOSED | 진행 중인 Battle (결과 비공개) |

---

## 4. 댓글

### 4-1. 댓글 작성

```
POST /api/v1/battles/{battleId}/comments
```

**인증**: 필요

**Request Body**

```json
{
  "content": "성수는 감성 카페가 많고, 연남은 독특한 맛집들이 많아서 고민되네요!"
}
```

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "commentId": 15,
    "content": "성수는 감성 카페가 많고, 연남은 독특한 맛집들이 많아서 고민되네요!",
    "authorNickname": "데이트고민러",
    "createdAt": "2026-05-28T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| UNAUTHORIZED | JWT 토큰 없음 또는 만료 |
| NOT_FOUND | Battle 없음 |
| BATTLE_CLOSED | 종료된 Battle |

---

### 4-2. 댓글 목록 조회

```
GET /api/v1/battles/{battleId}/comments?page={page}&size={size}
```

**인증**: 불필요

**Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| page | Integer | N | 페이지 번호 (기본: 0) |
| size | Integer | N | 페이지 크기 (기본: 10) |

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "content": [
      {
        "commentId": 15,
        "content": "성수는 감성 카페가 많고, 연남은 독특한 맛집들이 많아서 고민되네요!",
        "authorNickname": "데이트고민러",
        "isMyComment": false,
        "createdAt": "2026-05-28T10:00:00"
      }
    ],
    "totalElements": 23,
    "totalPages": 3,
    "size": 10,
    "number": 0
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| NOT_FOUND | Battle 없음 |

---

### 4-3. 댓글 삭제

```
DELETE /api/v1/battles/{battleId}/comments/{commentId}
```

**인증**: 필요

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": null,
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| UNAUTHORIZED | JWT 토큰 없음 또는 만료 |
| FORBIDDEN | 본인 댓글 아님 |
| NOT_FOUND | Battle 또는 댓글 없음 |

---

## 5. 내부 연계 API

### 5-1. Battle 투표 원본 데이터 조회 (Insight Service 전용)

```
GET /api/v1/battles/{battleId}/votes/raw
```

**인증**: 내부 서비스 인증

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

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| FORBIDDEN | 내부 서비스 인증 실패 |
| NOT_FOUND | Battle 없음 |
