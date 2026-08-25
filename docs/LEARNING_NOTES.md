# LEARNING_NOTES.md — 면접 관점 정리

Phase별로 "이 작업으로 답할 수 있게 된 면접 질문"과 모범 답변 뼈대를 누적한다.

---

## Phase 1: 멱등성 정석화 + Kafka/Outbox

### Q1. 멱등성 API를 어떻게 구현했는가?

**답변 뼈대:**
- point_history 테이블의 idempotency_key UNIQUE 제약을 동시성 락으로 활용했다
- PENDING 선삽입 패턴: status=PENDING으로 먼저 INSERT → 잔액 UPDATE → SUCCEEDED 확정을 단일 트랜잭션에서 수행
- 동일 키 동시 요청은 InnoDB 유니크 인덱스 락에서 직렬화되어, 선행 트랜잭션 커밋 후 후행 요청이 DuplicateKey로 떨어짐
- DuplicateKey catch 후 재조회하여 최초 응답을 그대로 반환 ("같은 요청 → 같은 응답" 원칙, 실패에도 적용)
- Testcontainers(MySQL)에서 10스레드 동시 요청으로 검증: 1건 성공 + 9건 ALREADY_PROCESSED, 5xx 0건

**깊이 질문 대비:**
- "왜 @Transactional 대신 TransactionTemplate을 썼나?" → DataIntegrityViolationException이 Hibernate 트랜잭션을 rollback-only로 마킹하기 때문. 트랜잭션 경계를 예외 발생 지점 안쪽으로 제한해야 catch 후 정상 반환 가능
- "H2로 테스트하면 안 되나?" → H2는 InnoDB의 유니크 인덱스 갭 락, REPEATABLE READ 스냅샷 동작이 다르다. 동시성/멱등성 테스트는 실제 MySQL 필수

### Q2. 어떤 기준으로 동기/비동기(이벤트)를 나눴는가?

**답변 뼈대:**
- 판단 기준: "실패가 원 행위를 막아야 하는가"
- **earn(적립)**: 투표의 부수효과. 보상 실패가 투표를 취소시키지 않음 → 이벤트(비동기) 대상
- **spend(차감)**: 잔액 부족이면 예측 자체가 성립 안 됨. 원 행위가 결과에 의존 → Saga(Phase 2)
- 비동기 전환의 실체: "투표는 Battle DB 커밋으로 즉시 성공, 포인트는 잠시 후 도착" — 결합도를 끊는다는 것의 구체적 의미

### Q3. Transactional Outbox 패턴을 왜, 어떻게 적용했는가?

**답변 뼈대:**
- **왜**: 이중 쓰기(dual write) 문제 해결. DB 커밋과 Kafka 발행을 동시에 보장할 수 없으므로, 이벤트를 DB에 먼저 기록하고 별도 프로세스가 Kafka로 발행
- **구현**: 비즈니스 변경(투표 저장, 정산 확정)과 outbox_event INSERT를 같은 트랜잭션에서 수행. 폴링 퍼블리셔가 5초 간격으로 PENDING 이벤트를 Kafka로 발행 후 PUBLISHED 전이
- **at-least-once 발행**: Kafka 발행 성공 후 DB UPDATE 전에 프로세스가 죽으면 다음 폴링에서 재발행 → 컨슈머 멱등성으로 중복 해소
- **폴링 vs CDC**: 1차는 폴링(단순), Phase 6에서 Debezium CDC로 전환 예정 (발행 지연 감소)

### Q4. 비동기로 바꾸면 사용자 경험은 어떻게 되나?

**답변 뼈대:**
- 최종 일관성(eventual consistency)의 사용자 노출: 투표 직후 잔액 조회 시 아직 반영 전일 수 있음
- 하지만 투표 자체는 즉시 성공하므로 사용자 체감 응답 속도는 오히려 개선
- 기존에는 Member-Point 다운 시 투표 응답까지 느려지거나 실패 경로를 탔지만, 이제는 Battle DB 커밋만으로 완료

### Q5. 컨슈머에서 실패를 어떻게 분류하는가?

**답변 뼈대:**
- 기존 REST의 "4xx 재시도 금지 / 5xx만 재시도" 정책의 이벤트 레벨 확장
- **비즈니스 거절** (MEMBER_NOT_FOUND, POINT_INVALID_AMOUNT 등): 재시도해도 결과 불변 → ack로 종결, 로그 기록
- **기술 실패** (DB 순단, 타임아웃): 재시도 가능 → 백오프 3회 → DLT
- **포이즌 메시지** (JSON 파싱 실패): 재시도 불가 → ack로 종결
- 이 분류가 없으면 탈퇴 회원 이벤트 하나가 재시도를 다 태우고 DLT를 오염시킨다
