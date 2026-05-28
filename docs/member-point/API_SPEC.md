
Claude configuration file at /Users/kimmo/.claude.json is corrupted: Unterminated string in JSON at position 204298

Claude configuration file at /Users/kimmo/.claude.json is corrupted
The corrupted file has been backed up to: /Users/kimmo/.claude.json.corrupted.1779973278578
A backup file exists at: /Users/kimmo/.claude.json.backup
You can manually restore it by running: cp "/Users/kimmo/.claude.json.backup" "/Users/kimmo/.claude.json"

# Member-Point Service API 명세

> Member-Point Service의 클라이언트용 및 Gateway용 API 명세이다.

---

## 1. 공통 사항

### 1-1. Base URL

```
http://member-point-service/api/v1
```

### 1-2. 인증 방식

- JWT Bearer Token 인증 사용
- Access Token 만료: 1시간
- Refresh Token 만료: 14일
- API Gateway가 JWT 검증 후 `X-Member-Id`, `X-Member-Role` 헤더로 사용자 정보 전달

### 1-3. 응답 포맷

모든 응답은 공통 `ApiResponse<T>` 포맷을 따른다. (`CONVENTION.md` 섹션 2 참조)

---

## 2. 인증 API

### 2-1. OAuth 소셜 로그인

```
POST /api/v1/auth/login
```

**인증**: 불필요

**Request Body**

```json
{
  "provider": "KAKAO",
  "authCode": "abc123xyz",
  "redirectUri": "http://localhost:3000/auth/callback"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| provider | String | Y | OAuth 제공자 (KAKAO, GOOGLE) |
| authCode | String | Y | OAuth Authorization Code |
| redirectUri | String | Y | OAuth Redirect URI |

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "memberId": 1,
    "nickname": "홍길동",
    "profileImageUrl": "https://example.com/profile.jpg",
    "pointBalance": 100,
    "isNewMember": false,
    "accessToken": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9..."
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| INVALID_OAUTH_CODE | OAuth Authorization Code 검증 실패 |
| OAUTH_PROVIDER_ERROR | OAuth 제공자 API 호출 실패 |

---

### 2-2. JWT 토큰 갱신

```
POST /api/v1/auth/refresh
```

**인증**: Refresh Token 필요

**Request Body**

```json
{
  "refreshToken": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9..."
}
```

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "accessToken": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9..."
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| UNAUTHORIZED | Refresh Token 만료 또는 유효하지 않음 |

---

### 2-3. 로그아웃

```
POST /api/v1/auth/logout
```

**인증**: Access Token 필요

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

---

## 3. 회원 API

### 3-1. 내 정보 조회

```
GET /api/v1/members/me
```

**인증**: Access Token 필요

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "memberId": 1,
    "email": "user@example.com",
    "nickname": "홍길동",
    "profileImageUrl": "https://example.com/profile.jpg",
    "pointBalance": 150,
    "residenceRegion": "서울특별시 강남구",
    "residenceUpdatedAt": "2026-05-01T10:00:00",
    "residenceCooldownUntil": null,
    "activityBadges": ["LOCAL_EXPERT", "PREDICTION_MASTER"],
    "joinedAt": "2026-01-15T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

---

### 3-2. 내 정보 수정

```
PATCH /api/v1/members/me
```

**인증**: Access Token 필요

**Request Body**

```json
{
  "nickname": "새로운닉네임",
  "profileImageUrl": "https://example.com/new-profile.jpg"
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
    "nickname": "새로운닉네임",
    "profileImageUrl": "https://example.com/new-profile.jpg"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| NICKNAME_DUPLICATED | 닉네임 중복 |
| NICKNAME_INVALID | 닉네임 형식 오류 (2-10자, 특수문자 제한) |

---

### 3-3. 거주지역 선언/수정

```
PATCH /api/v1/members/me/residence
```

**인증**: Access Token 필요

**Request Body**

```json
{
  "region": "서울특별시 성동구"
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
    "residenceRegion": "서울특별시 성동구",
    "residenceUpdatedAt": "2026-05-28T10:00:00",
    "residenceCooldownUntil": "2026-06-27T10:00:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| RESIDENCE_COOLDOWN | 변경 후 30일 쿨다운 중 |
| INVALID_REGION | 유효하지 않은 지역명 |

**거주지역 수정 정책**

- 변경 후 30일 쿨다운 적용
- 쿨다운 중 재요청 시 `RESIDENCE_COOLDOWN` 에러와 함께 `residenceCooldownUntil` 날짜 반환
- 변경 시 활동 확인 배지 초기화

---

### 3-4. 회원 탈퇴

```
DELETE /api/v1/members/me
```

**인증**: Access Token 필요

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

---

## 4. Point API

### 4-1. Point 잔액 조회

```
GET /api/v1/points/balance
```

**인증**: Access Token 필요

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "memberId": 1,
    "balance": 150.00,
    "lastUpdatedAt": "2026-05-28T09:30:00"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

---

### 4-2. Point 히스토리 조회

```
GET /api/v1/points/histories?page=0&size=20&type=ALL
```

**인증**: Access Token 필요

**Query Parameters**

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---|---|---|---|---|
| page | Integer | N | 0 | 페이지 번호 (0부터 시작) |
| size | Integer | N | 20 | 페이지 크기 (최대 100) |
| type | String | N | ALL | 히스토리 타입 (ALL, EARN, SPEND) |

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "content": [
      {
        "id": 123,
        "type": "EARN_VOTE",
        "amount": 10.00,
        "balanceSnapshot": 150.00,
        "reason": "Battle 투표 참여 보상",
        "referenceId": 42,
        "createdAt": "2026-05-28T09:30:00"
      },
      {
        "id": 122,
        "type": "SPEND_MARKET",
        "amount": -100.00,
        "balanceSnapshot": 140.00,
        "reason": "Market 예측 참여",
        "referenceId": 7,
        "createdAt": "2026-05-28T09:00:00"
      }
    ],
    "totalElements": 45,
    "totalPages": 3,
    "currentPage": 0,
    "pageSize": 20
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

---

## 5. 내부 연계 API (Gateway 전용)

### 5-1. 회원 정보 조회 (내부)

```
GET /api/v1/members/{memberId}/internal
```

**인증**: Gateway 내부 호출용 (별도 인증 없음)

**Response**

```json
{
  "success": true,
  "errorCode": null,
  "message": null,
  "data": {
    "memberId": 1,
    "email": "user@example.com",
    "nickname": "홍길동",
    "role": "USER",
    "status": "ACTIVE",
    "residenceRegion": "서울특별시 강남구"
  },
  "timestamp": "2026-05-28T10:00:00"
}
```

**Error Codes**

| 에러 코드 | 상황 |
|---|---|
| NOT_FOUND | 회원 없음 |

---

### 5-2. Point 관련 내부 연계 API

Point 적립, 차감, 정산, 환불 API는 **루트 API_SPEC.md 섹션 2** 참조

- `POST /api/v1/points/earn` - Point 적립
- `POST /api/v1/points/spend` - Point 차감  
- `POST /api/v1/points/settlements` - Market 정산 일괄 지급
- `POST /api/v1/points/refunds` - Market 무효 환불

---

## 6. ErrorCode 추가 정의

Member-Point Service에서 사용하는 추가 에러 코드이다.

| 에러 코드 | HTTP 상태 | 설명 |
|---|---:|---|
| INVALID_OAUTH_CODE | 400 | OAuth Authorization Code 검증 실패 |
| OAUTH_PROVIDER_ERROR | 502 | OAuth 제공자 API 호출 실패 |
| NICKNAME_DUPLICATED | 409 | 닉네임 중복 |
| NICKNAME_INVALID | 400 | 닉네임 형식 오류 |
| RESIDENCE_COOLDOWN | 400 | 거주지역 변경 쿨다운 중 |
| INVALID_REGION | 400 | 유효하지 않은 지역명 |
