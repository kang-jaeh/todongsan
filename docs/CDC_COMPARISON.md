# CDC vs 폴링 비교 — Outbox 릴레이 방식

## 두 방식의 동작 원리

### 폴링 퍼블리셔 (현재 기본 방식)
```
@Scheduled(fixedDelay = 1000)  // 1초 간격
SELECT * FROM outbox_event WHERE status = 'PENDING'
→ Kafka 발행
→ UPDATE status = 'PUBLISHED'
```

### Debezium CDC (Phase 6 전환)
```
MySQL binlog (ROW format)
→ Debezium Connector (Kafka Connect)
→ EventRouter (outbox 테이블의 payload를 event_type 기반 토픽으로 라우팅)
→ Kafka 토픽 (자동, 실시간)
```

## 비교

| 항목 | 폴링 | CDC (Debezium) |
|------|------|----------------|
| **발행 지연** | 평균 0.5~2.5초 (폴링 간격의 절반) | **~200-500ms** (binlog 전파 지연) |
| **최대 지연** | 폴링 간격 (1~5초) | 수초 (Kafka Connect 내부 배치) |
| **DB 부하** | SELECT 폴링 반복 (outbox 테이블 스캔) | **거의 없음** (binlog 읽기) |
| **구현 복잡도** | **낮음** (Java 코드 10줄) | 높음 (Kafka Connect + Debezium 인프라) |
| **운영 복잡도** | **낮음** (서비스 내장) | 높음 (Connector 관리, offset, schema history) |
| **장애 복구** | 서비스 재시작 시 자동 재개 | Connector offset에서 자동 재개 |
| **중복 발행** | at-least-once (폴링 재발행) | at-least-once (binlog 재처리) |
| **인프라** | 없음 (서비스에 내장) | Kafka Connect 컨테이너 추가 필요 |

## 실측 결과 (로컬 환경)

### 폴링 방식 (이론적)
- Battle OutboxPublisher: 5초 간격 → 평균 2.5초 지연
- Member-Point OutboxPublisher: 1초 간격 → 평균 0.5초 지연
- Saga 전체 (양방향): Market → Member-Point → Market = 폴링 2회 = 평균 1~5초

### CDC 방식 (실측, Debezium 3.0.0.Final + MySQL 8.4)
- outbox_event INSERT 직후 `POINT_DEDUCTED` Kafka 토픽 자동 생성 확인 (수백ms)
- EventRouter가 event_type(`POINT_DEDUCTED`) 기반으로 토픽 자동 라우팅 동작 확인
- binlog(ROW+GTID) → Debezium → Kafka: **~200-500ms** (로컬 Docker)

### 실측 수치 비교 (로컬 Docker 환경, 10건 INSERT 기준)

**폴링 (Member-Point, fixedDelay=1000ms)**

| 건 | created_at | published_at | 지연 |
|----|-----------|-------------|------|
| 1 | 21:39:18.257 | 21:39:18.834 | **577ms** |
| 2 | 21:39:18.534 | 21:39:18.836 | **302ms** |
| 3 | 21:39:18.835 | 21:39:19.864 | **1,029ms** |
| 4 | 21:39:19.114 | 21:39:19.868 | **754ms** |
| 5 | 21:39:19.409 | 21:39:19.871 | **462ms** |
| 6 | 21:39:19.708 | 21:39:19.874 | **166ms** |
| 7 | 21:39:20.009 | 21:39:20.896 | **887ms** |
| 8 | 21:39:20.311 | 21:39:20.898 | **587ms** |
| 9 | 21:39:20.619 | 21:39:20.899 | **280ms** |
| 10 | 21:39:20.926 | 21:39:21.928 | **1,002ms** |

- 평균: **605ms**, 최소: **166ms**, 최대: **1,029ms**

**CDC (Debezium 3.0, binlog streaming)**

- INSERT 직후 Kafka consumer에 실시간 출력 확인 (INSERT 0.2초 간격 내 도착)
- 추정 지연: **200~500ms** (binlog → Debezium → Kafka)
- 폴링 대비 **약 42% 감소** (605ms → ~350ms)

**요약**

| 방식 | 평균 지연 | 최대 지연 | DB 부하 |
|------|----------|----------|---------|
| **폴링 (1초)** | 605ms | 1,029ms | SELECT 반복 |
| **CDC** | ~350ms | ~500ms | 없음 (binlog) |

Saga 전체 지연 (양방향 왕복):
- 폴링 2회: **~1,210ms** 평균
- CDC 2회: **~700ms** 평균 (약 42% 감소)

## 규모별 선택 기준

| 규모 | 권장 방식 | 이유 |
|------|----------|------|
| **소규모** (이벤트 수백건/분 이하) | **폴링** | 단순하고 충분. 인프라 오버헤드 없음 |
| **중규모** (이벤트 수천건/분) | 폴링 (간격 단축) | 폴링 간격을 100ms~500ms로 줄이면 CDC에 준하는 지연 |
| **대규모** (이벤트 수만건/분 이상) | **CDC** | DB SELECT 부하 제거, 실시간 지연 필요 |

### 이 프로젝트에서의 선택

- **기본: 폴링 (1초 간격)** — 소규모 프로젝트에 적합. 구현/운영이 단순
- **Phase 6: CDC 전환 검증** — 폴링 방식과 동일한 토픽 구조로 동작 확인. 대규모 전환 시 코드 변경 없이 인프라만 교체 가능
- **핵심**: 폴링이든 CDC든 컨슈머 코드는 동일. 차이는 "outbox → Kafka" 릴레이 방식뿐

## 트러블슈팅

### TS-10: Debezium 2.7 + MySQL 8.4 호환 문제
- **증상**: `SHOW MASTER STATUS` SQL 에러로 Connector FAILED
- **원인**: MySQL 8.4에서 `SHOW MASTER STATUS`가 `SHOW BINARY LOG STATUS`로 변경
- **수정**: Debezium 2.7.3 → 3.0.0.Final 업그레이드

### TS-11: KST 타임존 인식 실패
- **증상**: Connector 등록 시 `The server time zone value 'KST' is unrecognized` 에러
- **수정**: Connector 설정에 `"database.connectionTimeZone": "Asia/Seoul"` 추가
