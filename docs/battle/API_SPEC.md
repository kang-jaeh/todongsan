# API_SPEC.md - Battle Service

> Battle Service의 클라이언트 대상 API + 서비스 간 내부 연계 API 명세이다.
> 에러 코드 상세는 `docs/battle/ERROR_CODE.md` 참조.

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

내부 서비스 간 호출은 별도 내부 인증을 사용한다 (게이트웨이 차단 + 서비스 토큰).

### 1-3. 응답 포맷

모든 응답은 공통 `ApiResponse<T>` 포맷을 따른다. `CONVENTION.md` 섹션 2 참조.

### 1-4. 정책 상수

| 항목 | 값 |
|---|---|
| Battle title 최대 길이 | 255자 |
| Battle option 최대 길이 | 100자 |
| 댓글 content 최대 길이 | 500자 |
| 페이징 기본 size | Battle 20, 댓글 10 |
| 종료 후 결과 전체 공개 기간 | 72시간 |

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
  "description": "주말 데이트 코스로 어디가 더 매력적인지 투표해주세요!",
  "startAt": "2026-05-29T00:00:00",
  "endAt": "2026-06-05T00:00:00"
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
    "startAt": "2026-05-29T00:00:00",
    "endAt": "2026-06-05T00:00:00",
    "createdAt": "2026-05-28T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| ErrorCode | HTTP | 상황 |
|---|---:|---|
| `UNAUTHORIZED` | 401 | JWT 없음/만료 |
| `VALIDATION_FAILED` | 400 | 필수 필드 누락, 길이 초과 |
| `BATTLE_INVALID_PERIOD` | 400 | endAt이 startAt 이전이거나 과거 |
| `POINT_INSUFFICIENT` | 400 | Battle 생성권 부족 |

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

**Response**: (기존 형식 유지)

**Error Codes**

| ErrorCode | HTTP | 상황 |
|---|---:|---|
| `VALIDATION_FAILED` | 400 | 잘못된 status 값 |

---

### 2-3. Battle 상세 조회

```
GET /api/v1/battles/{battleId}
```

**인증**: 불필요

**Response**: (기존 형식 유지)

**Error Codes**

| ErrorCode | HTTP | 상황 |
|---|---:|---|
| `BATTLE_NOT_FOUND` | 404 | 존재하지 않거나 soft delete됨 |

---

### 2-4. Battle 승인 (관리자)

```
PATCH /api/v1/battles/{battleId}/approve
```

**인증**: 필요 (관리자)

**Response**: (기존 형식 유지, status=ACTIVE)

**Error Codes**

| ErrorCode | HTTP | 상황 |
|---|---:|---|
| `UNAUTHORIZED` | 401 | — |
| `FORBIDDEN` | 403 | 관리자 권한 없음 |
| `BATTLE_NOT_FOUND` | 404 | — |
| `BATTLE_INVALID_STATUS` | 409 | PENDING 상태가 아님 (이미 승인/거절/취소됨) |

---

### 2-5. Battle 거절 (관리자)

```
PATCH /api/v1/battles/{battleId}/reject
```

**인증**: 필요 (관리자)

**Response**: (기존 형식 유지, status=CANCELLED)

**Error Codes**: 2-4와 동일

---

### 2-6. Battle 강제 취소 (관리자)

```
PATCH /api/v1/battles/{battleId}/cancel
```

**인증**: 필요 (관리자)

**Response**: (기존 형식 유지)

**Error Codes**

| ErrorCode | HTTP | 상황 |
|---|---:|---|
| `UNAUTHORIZED` | 401 | — |
| `FORBIDDEN` | 403 | 관리자 권한 없음 |
| `BATTLE_NOT_FOUND` | 404 | — |
| `BATTLE_INVALID_STATUS` | 409 | 이미 종료/취소된 Battle |

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

**Response**: (기존 형식 유지)

**상태 변화 메모**

- 정상: `battle_vote` 생성 + `battle.option_a_count`/`vote_count` +1, 그 후 Member-Point 보상 호출
- Point 호출 Timeout: 투표 유지, `point_reward_retry_queue` 적재 (사용자에게는 투표 성공 응답)
- 자세한 흐름은 `docs/battle/ERROR_CODE.md` 4-1 참조

**Error Codes**

| ErrorCode | HTTP | 상황 |
|---|---:|---|
| `UNAUTHORIZED` | 401 | — |
| `VALIDATION_FAILED` | 400 | option 필드 누락 |
| `BATTLE_INVALID_OPTION` | 400 | A/B 외의 값 |
| `BATTLE_NOT_FOUND` | 404 | — |
| `BATTLE_NOT_ACTIVE` | 400 | PENDING 상태 (아직 시작 전) |
| `BATTLE_CLOSED` | 409 | CLOSED/SETTLED/CANCELLED |
| `BATTLE_ALREADY_VOTED` | 409 | 중복 투표 |

---

### 3-2. 투표 결과 조회

```
GET /api/v1/battles/{battleId}/result
```

**인증**: 선택

**Response**: (기존 형식 유지 - 상태/투표여부/72h 경과 분기)

**Error Codes**

| ErrorCode | HTTP | 상황 |
|---|---:|---|
| `BATTLE_NOT_FOUND` | 404 | — |

---

### 3-3. 상세 교차분석 조회 (30P 소비)

```
GET /api/v1/battles/{battleId}/result/cross
```

**인증**: 필요

**Response**: (기존 형식 유지)

**상태 변화 메모**

- Point 차감은 검증 통과 후에만 호출. 검증 실패(4xx)는 Point 차감 없음.
- `Idempotency-Key` 헤더 필수 (Member-Point spend API 호출 시).

**Error Codes**

| ErrorCode | HTTP | 상황 |
|---|---:|---|
| `UNAUTHORIZED` | 401 | — |
| `BATTLE_NOT_FOUND` | 404 | — |
| `BATTLE_RESULT_NOT_AVAILABLE` | 409 | 진행 중인 Battle (CLOSED 아님) |
| `POINT_INSUFFICIENT` | 400 | 30P 부족 |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | 헤더 누락 |

---

### 3-4. 방문 인증자 필터 결과 (30P 소비)

```
GET /api/v1/battles/{battleId}/result/certified
```

**인증**: 필요

**Response**: (기존 형식 유지)

**Error Codes**: 3-3과 동일

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

**Response**: (기존 형식 유지)

**Error Codes**

| ErrorCode | HTTP | 상황 |
|---|---:|---|
| `UNAUTHORIZED` | 401 | — |
| `VALIDATION_FAILED` | 400 | content 빈 값 |
| `BATTLE_COMMENT_TOO_LONG` | 400 | 500자 초과 |
| `BATTLE_NOT_FOUND` | 404 | — |
| `BATTLE_NOT_ACTIVE` | 400 | PENDING 상태 |
| `BATTLE_CLOSED` | 409 | 종료된 Battle |

---

### 4-2. 댓글 목록 조회

```
GET /api/v1/battles/{battleId}/comments?page={page}&size={size}
```

**인증**: 불필요

**Response**: (기존 형식 유지)

**Error Codes**

| ErrorCode | HTTP | 상황 |
|---|---:|---|
| `BATTLE_NOT_FOUND` | 404 | — |

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

| ErrorCode | HTTP | 상황 |
|---|---:|---|
| `UNAUTHORIZED` | 401 | — |
| `BATTLE_COMMENT_FORBIDDEN` | 403 | 본인 댓글 아님 |
| `BATTLE_COMMENT_NOT_FOUND` | 404 | 존재하지 않거나 이미 삭제됨 |

---

## 5. 내부 연계 API

> 외부 노출 차단. 게이트웨이에서 외부 요청 차단 + 서비스 간 인증 토큰 검증 후 라우팅.

### 5-1. Battle 투표 원본 데이터 조회 (Insight Service 전용)

```
GET /api/v1/battles/{battleId}/votes/raw
```

**인증**: 내부 서비스 인증

**Response**: (기존 형식 유지)

**Error Codes**

| ErrorCode | HTTP | 상황 |
|---|---:|---|
| `FORBIDDEN` | 403 | 내부 서비스 인증 실패 |
| `BATTLE_NOT_FOUND` | 404 | — |

---

### 5-2. 댓글 단건 조회 (방문 인증용, Insight Service 전용)

```
GET /api/v1/battles/comments/{commentId}
```

**용도**: Insight-Reputation Service의 댓글 기반 방문 인증(`visit_certification.method=COMMENT`) 검증.
사용자가 "이 댓글로 지역 방문을 인증한다"고 요청하면 Insight가 이 API를 호출하여
댓글 존재 여부와 작성자/Battle 매핑을 확인한다.

**인증**: 내부 서비스 인증

**Path Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| commentId | Long | Y | 댓글 ID |

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "commentId": 15,
    "battleId": 42,
    "memberId": 678,
    "createdAt": "2026-05-28T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**응답 필드 정책**

- `content` (본문)와 닉네임은 응답에 포함하지 않음. 인증 검증에 불필요하고
  응답 비대화·개인정보 노출 최소화를 위함.
- soft delete된 댓글은 `BATTLE_COMMENT_NOT_FOUND`로 응답 (인증 무효 처리).
  → 인증 후 댓글을 지우는 우회를 방지.
- 향후 Insight가 추가 필드를 요구할 경우 합의 후 응답 스키마 확장.

**Error Codes**

| ErrorCode | HTTP | 상황 |
|---|---:|---|
| `FORBIDDEN` | 403 | 내부 서비스 인증 실패 |
| `BATTLE_COMMENT_NOT_FOUND` | 404 | 존재하지 않거나 soft delete됨 |

---

## 6. 변경 이력

| 일자 | 내용 |
|---|---|
| 2026-05-28 | 초안 작성 |
| 2026-05-29 | 에러 코드 가이드 기준 정비 (도메인 prefix, HTTP 상태 코드, 추가 케이스) |
| 2026-05-29 | 5-2 댓글 단건 조회 내부 API 추가 (Insight 요청) |
